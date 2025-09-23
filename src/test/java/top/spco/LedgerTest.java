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
package top.spco;

import org.junit.jupiter.api.Test;
import top.spco.cashflow.data.LedgerIO;
import top.spco.cashflow.data.LedgerIO.CategoryTaxonomy;
import top.spco.cashflow.data.MonthlyLedger;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class LedgerTest {

    public static final String fileName = "ledger-2025-09.cflg";

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DecimalFormat YUAN_FMT = new DecimalFormat("0.00");

    private static String fmtDateTime(long epochMs) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault()).format(DTF);
    }

    private static String fmtYuan(long cents) {
        return YUAN_FMT.format(BigDecimal.valueOf(cents).movePointLeft(2));
    }

    @Test
    public void writeTestV2() throws IOException {
        // 1) 定义“分类树”（类别 -> 子类别），每个类别至少 1 个子类别
        CategoryTaxonomy taxonomy = new CategoryTaxonomy(
                List.of(
                        "餐饮",
                        "饮料",
                        "工资"
                ),
                List.of(
                        List.of("正餐", "小吃"),     // 餐饮的子类
                        List.of("咖啡", "奶茶", "可乐", "雪碧"),   // 饮料的子类
                        List.of("固定收入")        // 工资的子类
                )
        );

        // 2) 创建 2025-09 的账本
        MonthlyLedger ledger = MonthlyLedger.of(YearMonth.of(2025, 9), 8, 256);

        // 3) 添加记录（子类别为必选）
        long t0 = System.currentTimeMillis();
        int catFood = taxonomy.categoryIdOf("餐饮");
        int subMeal = taxonomy.subIdOf(catFood, "正餐");

        int catDrink = taxonomy.categoryIdOf("饮料");
        int subCoffee = taxonomy.subIdOf(catDrink, "咖啡");
        int subMilkTea = taxonomy.subIdOf(catDrink, "奶茶");
        int subCoke = taxonomy.subIdOf(catDrink, "可乐");

        int catSalary = taxonomy.categoryIdOf("工资");
        int subFixInc = taxonomy.subIdOf(catSalary, "固定收入");

        ledger.add(t0, -2500, catFood, subMeal, "午饭：米线");
        ledger.add(t0 + 1000, -1200, catDrink, subCoffee, "美式咖啡");
        ledger.add(t0 + 2000, +150000, catSalary, subFixInc, "九月工资");
        ledger.add(t0 + 3000, -5000, catSalary, subFixInc, "麦当劳");
        ledger.add(t0 + 200, -4000, catDrink, subMilkTea, "美式奶茶");
        // 4) 保存到本地（v2：账本 + 分类树）
        File file = new File(fileName);
        LedgerIO.save(ledger, taxonomy, file);
        System.out.println("保存成功: " + file.getAbsolutePath());
    }

    @Test
    public void readTestV2() throws IOException {
        // 1) 从本地读取（拿到 ledger + taxonomy，一致自洽）
        File file = new File(fileName);
        LedgerIO.Bundle loaded = LedgerIO.load(file);

        // 2) 打印（用 \t 分隔；时间戳→人类可读日期；金额→元）
        System.out.println("读取后的账本记录：");
        int[] sorted = loaded.ledger.sortedIndicesByTimestampAsc();
        for (int idx : sorted) {
            MonthlyLedger.EntryView e = loaded.ledger.get(idx);
            String catName = loaded.taxonomy.categoryName(e.categoryId());
            String subName = loaded.taxonomy.subName(e.categoryId(), e.subCategoryId());

            System.out.print(fmtDateTime(e.timestamp()) + "\t");
            System.out.print(fmtYuan(e.amountInCents()) + "\t");
            System.out.print(catName + "\t");
            System.out.print(subName + "\t");
            System.out.print(e.noteUtf8());
            System.out.println();
        }
    }
}