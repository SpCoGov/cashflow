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

import top.spco.cashflow.importer.core.BillParser;
import top.spco.cashflow.importer.core.UnifiedTxn;

import java.io.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class WeChatBillParser implements BillParser {
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override public boolean supports(File f) {
        String n = f.getName().toLowerCase(Locale.ROOT);
        return n.endsWith(".xlsx") || n.endsWith(".csv") || n.endsWith(".txt");
    }

    @Override public List<UnifiedTxn> parse(File file) throws IOException {
        // 为简洁，这里只给出 CSV 路径；你可以复用之前的 .xlsx 代码返回 UnifiedTxn。
        if (file.getName().toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            return WeChatXlsx.read(file); // 你已有的 POI 解析，改为产出 UnifiedTxn
        }
        return parseCsv(file);
    }

    private static List<UnifiedTxn> parseCsv(File file) throws IOException {
        List<UnifiedTxn> out = new ArrayList<>();
        try (var br = new BufferedReader(new InputStreamReader(new FileInputStream(file)))) {
            String line; List<String[]> rows = new ArrayList<>();
            while ((line = br.readLine()) != null) {
                if (rows.isEmpty() && !line.isEmpty() && line.charAt(0) == '\uFEFF') line = line.substring(1);
                if (!line.isBlank()) rows.add(splitCsv(line));
            }
            int head = findHeader(rows);
            Map<String,Integer> col = mapHeader(rows.get(head));

            for (int i = head + 1; i < rows.size(); i++) {
                String[] r = rows.get(i);
                String time = get(r, col.get("交易时间"));
                if (time.isEmpty() || time.startsWith("---")) continue;

                String inout  = get(r, col.getOrDefault("收/支", -1));
                String amtStr = get(r, col.get("金额(元)"));
                String payee  = get(r, col.getOrDefault("交易对方", -1));
                String item   = get(r, col.getOrDefault("商品", -1));
                String note   = get(r, col.getOrDefault("备注", -1));

                long ts = LocalDateTime.parse(time, DTF).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                long cents = parseAmount(inout, amtStr);

                out.add(new UnifiedTxn(ts, cents, nz(payee), nz(item), nz(note)));
            }
        }
        return out;
    }

    private static long parseAmount(String inout, String amountYuan) {
        String t = amountYuan.replace("¥","").replace(",","").trim();
        long cents = new BigDecimal(t).setScale(2).movePointRight(2).longValueExact();
        int sign = (inout.contains("支") || inout.contains("出")) ? -1 : +1; // 粗略判别
        if (inout.contains("收") || inout.contains("入")) sign = +1;
        return sign * cents;
    }

    // ---- 一些 CSV/表头小工具（同你现有实现） ----
    private static int findHeader(List<String[]> rows) {
        for (int i = 0; i < Math.min(200, rows.size()); i++)
            if (rows.get(i).length>0 && "交易时间".equals(rows.get(i)[0].trim())) return i;
        throw new IllegalStateException("未找到表头：交易时间");
    }
    private static Map<String,Integer> mapHeader(String[] header) {
        Map<String,Integer> m = new HashMap<>();
        for (int i=0;i<header.length;i++) { var h = header[i]==null? "": header[i].trim(); if (!h.isEmpty()) m.put(h,i); }
        return m;
    }
    private static String get(String[] row, Integer i) { return (i==null||i<0||i>=row.length||row[i]==null)?"":row[i].trim(); }
    private static String[] splitCsv(String s){ var out=new ArrayList<String>(); var cur=new StringBuilder(); boolean q=false;
        for(int i=0;i<s.length();i++){char c=s.charAt(i); if(c=='\"'){ if(q&&i+1<s.length()&&s.charAt(i+1)=='\"'){cur.append('\"');i++;} else q=!q;}
        else if(c==','&&!q){out.add(cur.toString());cur.setLength(0);} else cur.append(c);}
        out.add(cur.toString()); for(int i=0;i<out.size();i++){var t=out.get(i).trim(); if(t.length()>=2 && t.startsWith("\"")&&t.endsWith("\"")) t=t.substring(1,t.length()-1); out.set(i,t);} return out.toArray(new String[0]);}
    private static String nz(String s){return s==null? "": s.trim();}
}