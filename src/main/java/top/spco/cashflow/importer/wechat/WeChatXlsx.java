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
package top.spco.cashflow.importer.wechat;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import top.spco.cashflow.importer.core.UnifiedTxn;
import top.spco.cashflow.util.StringUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 读取微信支付账单的 .xlsx，产出统一模型 UnifiedTxn 列表。
 */
public final class WeChatXlsx {

    private static final DateTimeFormatter[] TS_FMT = new DateTimeFormatter[]{
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/M/d H:mm"),
    };

    public static List<UnifiedTxn> read(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             Workbook wb = new XSSFWorkbook(fis)) {

            Sheet sheet = findSheetWithHeader(wb);
            int headerRowIdx = findHeaderRow(sheet);
            Map<String, Integer> col = mapHeader(sheet.getRow(headerRowIdx));

            List<UnifiedTxn> out = new ArrayList<>();
            int last = sheet.getLastRowNum();
            for (int r = headerRowIdx + 1; r <= last; r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;

                // 时间
                Cell cTime = getCell(row, col, "交易时间");
                if (cTime == null) continue;
                Long ts = readTimestampMillis(cTime);
                if (ts == null) {
                    String tsStr = str(cTime);
                    if (tsStr.isBlank()) continue;
                    ts = parseTimestamp(tsStr);
                }

                // 收/支、金额
                String inout = str(getCell(row, col, "收/支")); // 可能为空
                String amtStr = str(getCell(row, col, "金额(元)"));
                if (amtStr.isBlank()) continue;
                long amountCents = parseAmountCents(inout, amtStr);
                if (amountCents == 0) continue;
                // 交易对方 / 商品 / 备注 / 状态 等
                String payee = str(getCell(row, col, "交易对方"));
                String item = str(getCell(row, col, "商品"));
                String note = str(getCell(row, col, "备注"));
                if (note.equals("/")) note = "";

                out.add(new UnifiedTxn(ts, amountCents, StringUtil.trimToEmpty(payee), StringUtil.trimToEmpty(item), StringUtil.trimToEmpty(note)));
            }
            return out;
        }
    }

    // ---------- helpers ----------

    private static Sheet findSheetWithHeader(Workbook wb) {
        // 优先第一个能找到“交易时间”表头的 sheet
        for (int i = 0; i < wb.getNumberOfSheets(); i++) {
            Sheet s = wb.getSheetAt(i);
            try {
                findHeaderRow(s);
                return s;
            } catch (Exception ignore) {
            }
        }
        throw new IllegalStateException("未找到包含“交易时间”表头的工作表");
    }

    private static int findHeaderRow(Sheet s) {
        int maxScan = Math.min(100, s.getLastRowNum());
        for (int r = 0; r <= maxScan; r++) {
            Row row = s.getRow(r);
            if (row == null) continue;
            for (Cell c : row) {
                if ("交易时间".equals(str(c))) return r;
            }
        }
        throw new IllegalStateException("未找到表头：交易时间");
    }

    private static Map<String, Integer> mapHeader(Row header) {
        Map<String, Integer> m = new HashMap<>();
        for (Cell c : header) {
            String h = str(c);
            if (!h.isBlank()) m.put(h, c.getColumnIndex());
        }
        return m;
    }

    private static Cell getCell(Row row, Map<String, Integer> col, String name) {
        Integer i = col.get(name);
        return (i == null) ? null : row.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
    }

    private static String str(Cell c) {
        if (c == null) return "";
        return switch (c.getCellType()) {
            case STRING -> c.getStringCellValue().trim();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(c)) {
                    // 用统一字符串格式，留给上层解析或直接转毫秒
                    LocalDateTime ldt = LocalDateTime.ofInstant(c.getDateCellValue().toInstant(), ZoneId.systemDefault());
                    yield ldt.format(TS_FMT[0]);
                } else {
                    // 金额等数值转字符串
                    yield BigDecimal.valueOf(c.getNumericCellValue()).toPlainString();
                }
            }
            case BOOLEAN -> Boolean.toString(c.getBooleanCellValue());
            case FORMULA -> {
                try {
                    yield c.getStringCellValue().trim();
                } catch (Exception e) {
                    yield BigDecimal.valueOf(c.getNumericCellValue()).toPlainString();
                }
            }
            default -> "";
        };
    }

    private static Long readTimestampMillis(Cell c) {
        if (c == null) return null;
        if (c.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(c)) {
            Date d = c.getDateCellValue();
            return d.toInstant().toEpochMilli();
        }
        return null;
    }

    private static long parseTimestamp(String s) {
        s = s.trim();
        for (DateTimeFormatter f : TS_FMT) {
            try {
                return LocalDateTime.parse(s, f)
                        .atZone(ZoneId.systemDefault())
                        .toInstant().toEpochMilli();
            } catch (Exception ignore) {
            }
        }
        throw new IllegalArgumentException("无法解析交易时间: " + s);
    }

    private static long parseAmountCents(String inout, String amountYuan) {
        String t = amountYuan.replace("¥", "").replace(",", "").trim();
        boolean negBySign = t.startsWith("-");
        if (negBySign) t = t.substring(1).trim();

        long abs = new BigDecimal(t).setScale(2).movePointRight(2).longValueExact();

        int sign = negBySign ? -1 : +1;
        String io = StringUtil.trimToEmpty(inout);
        if (io.equals("/")) return 0;
        if (io.contains("支")) sign = -1;       // “支出”
        if (io.contains("收")) sign = +1;       // “收入”
        return sign * abs;
    }

}