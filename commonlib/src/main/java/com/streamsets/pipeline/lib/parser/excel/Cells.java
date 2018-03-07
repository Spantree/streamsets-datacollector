/*
 * Copyright 2018 StreamSets Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. See accompanying LICENSE file
 */

package com.streamsets.pipeline.lib.parser.excel;

import com.streamsets.pipeline.api.Field;
import com.streamsets.pipeline.lib.parser.DataParserException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;

class Cells {
  static Field parseCell(Cell cell) throws DataParserException {
    CellType cellType = cell.getCellTypeEnum();
    if (cell.getCellTypeEnum().equals(CellType.FORMULA)) {
      cellType = cell.getCachedFormulaResultTypeEnum();
    }
    switch (cellType) {
      case STRING:
        return Field.create(cell.getStringCellValue());
      case NUMERIC:
        return Field.create(cell.getNumericCellValue());
      case BOOLEAN:
        return Field.create(cell.getBooleanCellValue());
      case BLANK:
        return Field.create("");
      default:
        throw new DataParserException(Errors.EXCEL_PARSER_05, cellType.toString());
    }
  }
}
