/**
 * Copyright 2015 StreamSets Inc.
 *
 * Licensed under the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.pipeline.stage.destination.elasticsearch;

import com.google.common.annotations.VisibleForTesting;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.streamsets.pipeline.api.Batch;
import com.streamsets.pipeline.api.ErrorCode;
import com.streamsets.pipeline.api.Record;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.api.base.BaseTarget;
import com.streamsets.pipeline.api.base.OnRecordErrorException;
import com.streamsets.pipeline.api.el.ELEval;
import com.streamsets.pipeline.api.el.ELEvalException;
import com.streamsets.pipeline.api.el.ELVars;
import com.streamsets.pipeline.api.impl.Utils;
import com.streamsets.pipeline.config.JsonMode;
import com.streamsets.pipeline.lib.el.RecordEL;
import com.streamsets.pipeline.lib.el.TimeEL;
import com.streamsets.pipeline.lib.el.TimeNowEL;
import com.streamsets.pipeline.lib.generator.DataGenerator;
import com.streamsets.pipeline.lib.generator.DataGeneratorFactory;
import com.streamsets.pipeline.lib.generator.DataGeneratorFactoryBuilder;
import com.streamsets.pipeline.lib.generator.DataGeneratorFormat;
import com.streamsets.pipeline.lib.operation.OperationType;
import com.streamsets.pipeline.stage.common.DefaultErrorRecordHandler;
import com.streamsets.pipeline.stage.common.ErrorRecordHandler;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.StringUtils;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.apache.http.message.BasicHeader;
import org.apache.http.ssl.SSLContexts;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.elasticsearch.client.sniff.ElasticsearchHostsSniffer;
import org.elasticsearch.client.sniff.HostsSniffer;
import org.elasticsearch.client.sniff.Sniffer;
import org.elasticsearch.common.io.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ElasticSearchTarget extends BaseTarget {
  private static final Logger LOG = LoggerFactory.getLogger(ElasticSearchTarget.class);
  private static final Pattern URI_PATTERN = Pattern.compile("\\S+:(\\d+)");
  private static final Pattern SECURITY_USER_PATTERN = Pattern.compile("\\S+:\\S+");
  private final ElasticSearchConfigBean conf;
  private ELEval timeDriverEval;
  private TimeZone timeZone;
  private Date batchTime;
  private ELEval indexEval;
  private ELEval typeEval;
  private ELEval docIdEval;
  private ELEval parentIdEval;
  private DataGeneratorFactory generatorFactory;
  private ErrorRecordHandler errorRecordHandler;
  private RestClient restClient;
  private Sniffer sniffer;

  public ElasticSearchTarget(ElasticSearchConfigBean conf) {
    this.conf = conf;
    if (this.conf.params == null) {
      this.conf.params = new HashMap<>();
    }
    this.timeZone = TimeZone.getTimeZone(conf.timeZoneID);
  }

  private void validateEL(ELEval elEval, String elStr, String config, ErrorCode parseError, ErrorCode evalError,
      List<ConfigIssue> issues) {
    ELVars vars = getContext().createELVars();
    RecordEL.setRecordInContext(vars, getContext().createRecord("validateConfigs"));
    TimeEL.setCalendarInContext(vars, Calendar.getInstance());
    try {
      getContext().parseEL(elStr);
    } catch (ELEvalException ex) {
      issues.add(getContext().createConfigIssue(Groups.ELASTIC_SEARCH.name(), config, parseError, ex.toString(), ex));
      return;
    }
    try {
      elEval.eval(vars, elStr, String.class);
    } catch (ELEvalException ex) {
      issues.add(getContext().createConfigIssue(Groups.ELASTIC_SEARCH.name(), config, evalError, ex.toString(), ex));
    }
  }

  @Override
  protected List<ConfigIssue> init() {
    List<ConfigIssue> issues = super.init();
    errorRecordHandler = new DefaultErrorRecordHandler(getContext());

    indexEval = getContext().createELEval("indexTemplate");
    typeEval = getContext().createELEval("typeTemplate");
    docIdEval = getContext().createELEval("docIdTemplate");
    parentIdEval = getContext().createELEval("parentIdTemplate");
    timeDriverEval = getContext().createELEval("timeDriver");

    try {
      getRecordTime(getContext().createRecord("validateTimeDriver"));
    } catch (ELEvalException ex) {
      issues.add(getContext().createConfigIssue(
          Groups.ELASTIC_SEARCH.name(),
          "timeDriverEval",
          Errors.ELASTICSEARCH_18,
          ex.toString(),
          ex
      ));
    }

    validateEL(
        indexEval,
        conf.indexTemplate,
        ElasticSearchConfigBean.CONF_PREFIX + "indexTemplate",
        Errors.ELASTICSEARCH_00,
        Errors.ELASTICSEARCH_01,
        issues
    );
    validateEL(
        typeEval,
        conf.typeTemplate,
        ElasticSearchConfigBean.CONF_PREFIX + "typeTemplate",
        Errors.ELASTICSEARCH_02,
        Errors.ELASTICSEARCH_03,
        issues
    );
    if (!StringUtils.isEmpty(conf.docIdTemplate)) {
      validateEL(
          typeEval,
          conf.docIdTemplate,
          ElasticSearchConfigBean.CONF_PREFIX + "docIdTemplate",
          Errors.ELASTICSEARCH_04,
          Errors.ELASTICSEARCH_05,
          issues
      );
    } else {
      if (conf.defaultOperation != ElasticSearchOperationType.INDEX) {
        issues.add(
            getContext().createConfigIssue(
                Groups.ELASTIC_SEARCH.name(),
                ElasticSearchConfigBean.CONF_PREFIX + "docIdTemplate",
                Errors.ELASTICSEARCH_19,
                conf.defaultOperation.getLabel()
            )
        );
      }
    }
    if (!StringUtils.isEmpty(conf.parentIdTemplate)) {
      validateEL(
              typeEval,
              conf.parentIdTemplate,
              ElasticSearchConfigBean.CONF_PREFIX + "parentIdTemplate",
              Errors.ELASTICSEARCH_21,
              Errors.ELASTICSEARCH_22,
              issues
      );
    }

    if (conf.httpUris.isEmpty()) {
      issues.add(
          getContext().createConfigIssue(
              Groups.ELASTIC_SEARCH.name(),
              ElasticSearchConfigBean.CONF_PREFIX + "httpUris",
              Errors.ELASTICSEARCH_06
          )
      );
    } else {
      for (String uri : conf.httpUris) {
        validateUri(uri, issues, ElasticSearchConfigBean.CONF_PREFIX + "httpUris");
      }
    }

    if (conf.useSecurity) {
      if (!SECURITY_USER_PATTERN.matcher(conf.securityConfigBean.securityUser).matches()) {
        issues.add(
            getContext().createConfigIssue(
                Groups.SECURITY.name(),
                SecurityConfigBean.CONF_PREFIX + "securityUser",
                Errors.ELASTICSEARCH_20,
                conf.securityConfigBean.securityUser
            )
        );
      }
    }

    if (!issues.isEmpty()) {
      return issues;
    }

    int numHosts = conf.httpUris.size();
    HttpHost[] hosts = new HttpHost[numHosts];
    for (int i = 0; i < numHosts; i++) {
      hosts[i] = HttpHost.create(conf.httpUris.get(i));
    }
    RestClientBuilder restClientBuilder = RestClient.builder(hosts);

    try {
      if (conf.useSecurity) {
        try {
          final SSLContext sslcontext;
          final String keystorePath = conf.securityConfigBean.sslTruststorePath;
          if (StringUtils.isEmpty(keystorePath)) {
            sslcontext = SSLContext.getDefault();
          } else {
            final String keystorePass = conf.securityConfigBean.sslTruststorePassword;
            if (StringUtils.isEmpty(keystorePass)) {
              issues.add(
                  getContext().createConfigIssue(
                      Groups.ELASTIC_SEARCH.name(),
                      SecurityConfigBean.CONF_PREFIX + "sslTruststorePassword",
                      Errors.ELASTICSEARCH_10
                  )
              );
            }
            Path path = PathUtils.get(keystorePath);
            if (!Files.exists(path)) {
              issues.add(
                  getContext().createConfigIssue(
                      Groups.ELASTIC_SEARCH.name(),
                      SecurityConfigBean.CONF_PREFIX + "sslTruststorePath",
                      Errors.ELASTICSEARCH_11,
                      keystorePath
                  )
              );
            }
            KeyStore keyStore = KeyStore.getInstance("jks");
            try (InputStream is = Files.newInputStream(path)) {
              keyStore.load(is, keystorePass.toCharArray());
            }
            sslcontext = SSLContexts.custom().loadTrustMaterial(keyStore, null).build();
          }
          restClientBuilder.setHttpClientConfigCallback(
            new RestClientBuilder.HttpClientConfigCallback() {
              @Override
              public HttpAsyncClientBuilder customizeHttpClient(HttpAsyncClientBuilder httpClientBuilder) {
                return httpClientBuilder.setSSLContext(sslcontext);
              }
            }
          );
        } catch (KeyStoreException | NoSuchAlgorithmException | KeyManagementException | CertificateException e) {
          issues.add(
              getContext().createConfigIssue(
                  Groups.ELASTIC_SEARCH.name(),
                  SecurityConfigBean.CONF_PREFIX + "sslTruststorePath",
                  Errors.ELASTICSEARCH_12,
                  e.toString(),
                  e
              )
          );
        }

        restClient = restClientBuilder.build();
        restClient.performRequest("GET", "/", getAuthenticationHeader());
      } else {
        restClient = restClientBuilder.build();
        restClient.performRequest("GET", "/");
      }
    } catch (IOException e) {
      issues.add(
          getContext().createConfigIssue(
              Groups.ELASTIC_SEARCH.name(),
              ElasticSearchConfigBean.CONF_PREFIX + "httpUris",
              Errors.ELASTICSEARCH_09,
              e.toString(),
              e
          )
      );
    }

    if (!issues.isEmpty()) {
      return issues;
    }

    if (conf.clientSniff) {
      switch (hosts[0].getSchemeName()) {
        case "http": {
          sniffer = Sniffer.builder(restClient).build();
          break;
        }
        case "https": {
          HostsSniffer hostsSniffer = new ElasticsearchHostsSniffer(restClient,
              ElasticsearchHostsSniffer.DEFAULT_SNIFF_REQUEST_TIMEOUT,
              ElasticsearchHostsSniffer.Scheme.HTTPS);
          sniffer = Sniffer.builder(restClient).setHostsSniffer(hostsSniffer).build();
          break;
        }
        default:
          // unsupported scheme. do nothing.
      }
    }

    generatorFactory = new DataGeneratorFactoryBuilder(getContext(), DataGeneratorFormat.JSON)
        .setMode(JsonMode.MULTIPLE_OBJECTS)
        .setCharset(Charset.forName(conf.charset))
        .build();

    return issues;
  }

  @Override
  public void destroy() {
    try {
      if (sniffer != null) {
        sniffer.close();
      }
      if (restClient != null) {
        restClient.close();
      }
    } catch (IOException e) {
      LOG.warn("Exception thrown while closing REST client: " + e);
    }
    super.destroy();
  }

  @VisibleForTesting
  Date getRecordTime(Record record) throws ELEvalException {
    ELVars variables = getContext().createELVars();
    TimeNowEL.setTimeNowInContext(variables, getBatchTime());
    RecordEL.setRecordInContext(variables, record);
    return timeDriverEval.eval(variables, conf.timeDriver, Date.class);
  }

  @VisibleForTesting
  String getRecordIndex(ELVars elVars, Record record) throws ELEvalException {
    Date date = getRecordTime(record);
    if (date != null) {
      Calendar calendar = Calendar.getInstance(timeZone);
      calendar.setTime(date);
      TimeEL.setCalendarInContext(elVars, calendar);
    }
    return indexEval.eval(elVars, conf.indexTemplate, String.class);
  }

  @Override
  public void write(final Batch batch) throws StageException {
    setBatchTime();
    ELVars elVars = getContext().createELVars();
    TimeNowEL.setTimeNowInContext(elVars, getBatchTime());
    Iterator<Record> it = batch.getRecords();

    StringBuilder bulkRequest = new StringBuilder();

    //we need to keep the records in order of appearance in case we have indexing errors
    //and error handling is TO_ERROR
    List<Record> records = new ArrayList<>();

    while (it.hasNext()) {
      Record record = it.next();
      records.add(record);

      try {
        RecordEL.setRecordInContext(elVars, record);
        String index = getRecordIndex(elVars, record);
        String type = typeEval.eval(elVars, conf.typeTemplate, String.class);
        String id = null;
        if (!StringUtils.isEmpty(conf.docIdTemplate)) {
          id = docIdEval.eval(elVars, conf.docIdTemplate, String.class);
        }
        String parent = null;
        if (!StringUtils.isEmpty(conf.parentIdTemplate)) {
          parent = parentIdEval.eval(elVars, conf.parentIdTemplate, String.class);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataGenerator generator = generatorFactory.getGenerator(baos);
        generator.write(record);
        generator.close();

        int opCode = -1;
        String opType = record.getHeader().getAttribute(OperationType.SDC_OPERATION_TYPE);
        String recordJson = new String(baos.toByteArray(), StandardCharsets.UTF_8).replace("\n", "");
        // Check if the operation code from header attribute is valid
        if (!StringUtils.isEmpty(opType)) {
          try {
            opCode = ElasticSearchOperationType.convertToIntCode(opType);
          } catch (NumberFormatException | UnsupportedOperationException ex) {
            // Operation obtained from header is not supported. Handle accordingly
            switch (conf.unsupportedAction) {
              case DISCARD:
                LOG.debug("Discarding record with unsupported operation {}", opType);
                break;
              case SEND_TO_ERROR:
                errorRecordHandler.onError(new OnRecordErrorException(record, Errors.ELASTICSEARCH_13, ex.getMessage(), ex));
                break;
              case USE_DEFAULT:
                opCode = conf.defaultOperation.code;
                break;
              default: //unknown action
                errorRecordHandler.onError(new OnRecordErrorException(record, Errors.ELASTICSEARCH_14, ex.getMessage(), ex));
            }
          }
        } else {
          // No header attribute set. Use default.
          opCode = conf.defaultOperation.code;
        }
        bulkRequest.append(getOperation(index, type, id, parent, recordJson, opCode));
      } catch (IOException ex) {
        errorRecordHandler.onError(
            new OnRecordErrorException(
                record,
                Errors.ELASTICSEARCH_15,
                record.getHeader().getSourceId(),
                ex.toString(),
                ex
            )
        );
      }
    }

    if (!records.isEmpty()) {
      try {
        HttpEntity entity = new StringEntity(bulkRequest.toString(), ContentType.APPLICATION_JSON);
        Response response;
        if (conf.useSecurity) {
          response = restClient.performRequest("POST", "/_bulk", conf.params, entity, getAuthenticationHeader());
        } else {
          response = restClient.performRequest("POST", "/_bulk", conf.params, entity);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        response.getEntity().writeTo(baos);
        JsonObject json = new JsonParser().parse(baos.toString()).getAsJsonObject();
        baos.close();

        // Handle errors in bulk requests individually.
        boolean errors = json.get("errors").getAsBoolean();
        if (errors) {
          List<ErrorItem> errorItems;
          switch (getContext().getOnErrorRecord()) {
            case DISCARD:
              break;
            case TO_ERROR:
              errorItems = extractErrorItems(json);
              for (ErrorItem item : errorItems) {
                Record record = records.get(item.index);
                getContext().toError(record, Errors.ELASTICSEARCH_16, record.getHeader().getSourceId(), item.reason);
              }
              break;
            case STOP_PIPELINE:
              errorItems = extractErrorItems(json);
              throw new StageException(Errors.ELASTICSEARCH_17, errorItems.size(), "One or more operations failed");
            default:
              throw new IllegalStateException(
                  Utils.format("Unknown OnError value '{}'", getContext().getOnErrorRecord())
              );
          }
        }
      } catch (IOException ex) {
        errorRecordHandler.onError(records, new StageException(Errors.ELASTICSEARCH_17, records.size(), ex.toString(), ex));
      }
    }
  }

  Date setBatchTime() {
    batchTime = new Date();
    return batchTime;
  }

  Date getBatchTime() {
    return batchTime;
  }

  private void validateUri(String uri, List<ConfigIssue> issues, String configName) {
    Matcher matcher = URI_PATTERN.matcher(uri);
    if (!matcher.matches()) {
      issues.add(
          getContext().createConfigIssue(
              Groups.ELASTIC_SEARCH.name(),
              configName,
              Errors.ELASTICSEARCH_07,
              uri
          )
      );
    } else {
      int port = Integer.parseInt(matcher.group(1));
      if (port < 0 || port > 65535) {
        issues.add(
            getContext().createConfigIssue(
                Groups.ELASTIC_SEARCH.name(),
                configName,
                Errors.ELASTICSEARCH_08,
                port
            )
        );
      }
    }
  }

  private String getOperation(String index, String type, String id, String parent, String record, int opCode) {
    StringBuilder op = new StringBuilder();
    switch (opCode) {
      case OperationType.UPSERT_CODE:
        op.append(String.format("{\"index\":%s}%n", getOperationMetadata(index, type, id, parent)));
        op.append(String.format("%s%n", record));
        break;
      case OperationType.INSERT_CODE:
        op.append(String.format("{\"create\":%s}%n", getOperationMetadata(index, type, id, parent)));
        op.append(String.format("%s%n", record));
        break;
      case OperationType.UPDATE_CODE:
        op.append(String.format("{\"update\":%s}%n", getOperationMetadata(index, type, id, parent)));
        op.append(String.format("{\"doc\":%s}%n", record));
        break;
      case OperationType.DELETE_CODE:
        op.append(String.format("{\"delete\":%s}%n", getOperationMetadata(index, type, id, parent)));
        break;
      default:
        LOG.error("Operation {} not supported", opCode);
        throw new UnsupportedOperationException(String.format("Unsupported Operation: %s", opCode));
    }
    return op.toString();
  }

  private String getOperationMetadata(String index, String type, String id, String parent) {
    String indexOp = String.format("{\"_index\":\"%s\",\"_type\":\"%s\"", index, type);
    
    if (!StringUtils.isEmpty(id)) {
      indexOp += String.format(",\"_id\":\"%s\"", id);
    }
    if (!StringUtils.isEmpty(parent)) {
      indexOp += String.format(",\"parent\":\"%s\"", parent);
    }
    indexOp += "}";

    return indexOp;
  }

  private Header getAuthenticationHeader() {
    // Credentials are in form of "username:password".
    byte[] credentials = conf.securityConfigBean.securityUser.getBytes();
    return new BasicHeader("Authorization", "Basic " + Base64.encodeBase64String(credentials));
  }

  private List<ErrorItem> extractErrorItems(JsonObject json) {
    List<ErrorItem> errorItems = new ArrayList<>();
    JsonArray items = json.getAsJsonArray("items");
    for (int i = 0; i < items.size(); i++) {
      JsonObject item = items.get(i).getAsJsonObject().entrySet().iterator().next().getValue().getAsJsonObject();
      int status = item.get("status").getAsInt();
      if (status >= 400) {
        Object error = item.get("error");
        // In some old versions, "error" is a simple string not a json object.
        if (error instanceof JsonObject) {
          errorItems.add(new ErrorItem(i, item.getAsJsonObject("error").get("reason").getAsString()));
        } else if (error instanceof JsonPrimitive) {
          errorItems.add(new ErrorItem(i, item.getAsJsonPrimitive("error").getAsString()));
        } else {
          // Error would be null if json has no "error" field.
          errorItems.add(new ErrorItem(i, ""));
        }
      }
    }
    return errorItems;
  }

  private static class ErrorItem {
    int index;
    String reason;
    ErrorItem(int index, String reason) {
      this.index = index;
      this.reason = reason;
    }
  }
}
