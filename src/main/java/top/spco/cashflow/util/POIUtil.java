/*
 * Copyright 2025 SpCo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package top.spco.cashflow.util;

import org.apache.poi.ss.usermodel.*;

public class POIUtil {
    public static int writeKV(Sheet s, int row, String key, double val, CellStyle valStyle, CellStyle keyHdr) {
        Row r = s.createRow(row++);
        Cell c0 = cell(r, 0);
        c0.setCellValue(key);
        c0.setCellStyle(keyHdr);
        Cell c1 = cell(r, 1);
        c1.setCellValue(val);
        c1.setCellStyle(valStyle);
        return row;
    }

    public static int writeKV(Sheet s, int row, String key, long val, CellStyle valStyle, CellStyle keyHdr) {
        Row r = s.createRow(row++);
        Cell c0 = cell(r, 0);
        c0.setCellValue(key);
        c0.setCellStyle(keyHdr);
        Cell c1 = cell(r, 1);
        c1.setCellValue(val);
        c1.setCellStyle(valStyle);
        return row;
    }

    public static void writeHeader(Sheet s, int row, String[] headers, CellStyle hdr) {
        Row r = s.createRow(row);
        for (int i = 0; i < headers.length; i++) {
            Cell c = cell(r, i);
            c.setCellValue(headers[i]);
            c.setCellStyle(hdr);
        }
    }

    public static Cell cell(Row r, int col) {
        return r.createCell(col, CellType.BLANK);
    }

    public static void num(Row r, int col, long v, CellStyle st) {
        Cell c = cell(r, col);
        c.setCellValue(v);
        c.setCellStyle(st);
    }

    public static void num(Row r, int col, double v, CellStyle st) {
        Cell c = cell(r, col);
        c.setCellValue(v);
        c.setCellStyle(st);
    }

    public static void autoSize(Sheet s, int fromInclusive, int toInclusive) {
        for (int i = fromInclusive; i <= toInclusive; i++) s.autoSizeColumn(i);
    }
}