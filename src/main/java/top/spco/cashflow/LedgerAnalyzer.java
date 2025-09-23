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
package top.spco.cashflow;

import javafx.beans.property.*;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import top.spco.cashflow.data.LedgerIO;
import top.spco.cashflow.data.MonthlyLedger;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.*;

/** 账本分析器：读取文件 -> 统计 -> 横向图表 / 分栏明细 / 子类占比 */
public final class LedgerAnalyzer {

    private static final DecimalFormat YUAN_FMT = new DecimalFormat("0.00");
    private static final DecimalFormat PCT_FMT  = new DecimalFormat("0.00%");

    private LedgerAnalyzer() {}

    // === 入口：显示分析窗口 ===
    public static void showAnalysis(Stage owner, File file) throws IOException {
        LedgerIO.Bundle bundle = LedgerIO.load(file);
        Analysis a = analyzeBundle(bundle);

        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.setTitle("分析 - " + file.getName());

        TabPane tabs = new TabPane();
        tabs.getTabs().addAll(
                buildOverviewTab(a),     // 概览（横向：饼图 + 柱图）
                buildDetailsTab(a),      // 明细（横向：类别表 + 子类表）
                buildSubShareTab(a)      // 子类占比（按类别筛选的饼图 + 表）
        );

        Scene scene = new Scene(tabs, 1200, 750);
        stage.setScene(scene);
        stage.show();
    }

    // === 统计计算 ===
    private static Analysis analyzeBundle(LedgerIO.Bundle b) {
        Map<String, Stat> catStats = new LinkedHashMap<>();
        Map<String, Map<String, Stat>> subStats = new LinkedHashMap<>();
        long totalIncome = 0L;     // >0 的和（分）
        long totalExpenseAbs = 0L; // 所有支出绝对值之和（分）
        long netCents = 0L;        // 净额（分）
        long count = 0L;

        int[] order = b.ledger.sortedIndicesByTimestampAsc();
        for (int idx : order) {
            MonthlyLedger.EntryView e = b.ledger.get(idx);
            String cat = b.taxonomy.categoryName(e.categoryId());
            String sub = b.taxonomy.subName(e.categoryId(), e.subCategoryId());
            long amount = e.amountInCents();
            boolean income = amount > 0;

            Stat cs = catStats.computeIfAbsent(cat, k -> new Stat(cat, null));
            Stat ss = subStats.computeIfAbsent(cat, k -> new LinkedHashMap<>())
                    .computeIfAbsent(sub, k -> new Stat(cat, sub));

            for (Stat s : List.of(cs, ss)) {
                s.count++;
                s.netCents += amount;
                if (income) {
                    s.incomeCents += amount;
                } else {
                    s.expenseCents += amount;          // 负数累计
                    s.expenseCount++;
                    if (amount < s.maxExpenseCents) {  // 更“负”的视为更大支出
                        s.maxExpenseCents = amount;
                    }
                }
            }

            if (income) totalIncome += amount; else totalExpenseAbs += -amount;
            netCents += amount;
            count++;
        }

        // 占比（按支出）与平均支出（仅负数）
        for (Stat s : catStats.values()) {
            s.expenseShare = (totalExpenseAbs == 0) ? 0d
                    : (Math.abs(s.expenseCents) * 1.0) / totalExpenseAbs;  // 类别在全局支出占比
            s.avgExpenseCents = (s.expenseCount == 0) ? 0L
                    : Math.round((double)s.expenseCents / s.expenseCount); // 仍为负数
        }
        for (Map<String, Stat> m : subStats.values()) {
            long catExpenseAbs = 0L;
            for (Stat s : m.values()) catExpenseAbs += Math.abs(s.expenseCents);
            for (Stat s : m.values()) {
                s.expenseShare = (catExpenseAbs == 0) ? 0d
                        : (Math.abs(s.expenseCents) * 1.0) / catExpenseAbs; // 子类在所属类别支出占比
                s.avgExpenseCents = (s.expenseCount == 0) ? 0L
                        : Math.round((double)s.expenseCents / s.expenseCount);
            }
        }

        return new Analysis(catStats, subStats, totalIncome, totalExpenseAbs, netCents, count);
    }

    // === 概览（横向图表） ===
    private static Tab buildOverviewTab(Analysis a) {
        // 顶部概览数字
        GridPane top = new GridPane();
        top.setHgap(16); top.setVgap(6); top.setPadding(new Insets(12));
        top.addRow(0, bold("总收入（元）:"), new Label(fmtYuan(a.totalIncome)),
                bold("总支出（元）:"), new Label(fmtYuan(-a.totalExpenseAbs)),
                bold("净额（元）:"),   new Label(fmtYuan(a.netCents)),
                bold("记录数:"),     new Label(String.valueOf(a.count)));

        // 左：类别支出占比饼图
        PieChart pie = new PieChart();
        pie.setTitle("类别支出占比");
        for (Stat s : a.catStats.values()) {
            if (Math.abs(s.expenseCents) == 0) continue;
            PieChart.Data d = new PieChart.Data(
                    s.category + "  " + fmtPct(s.expenseShare),
                    centsToYuanDouble(Math.abs(s.expenseCents)));
            pie.getData().add(d);
        }

        // 右：Top 10 类别支出柱图
        CategoryAxis x = new CategoryAxis();
        NumberAxis y = new NumberAxis();
        y.setLabel("支出（元，绝对值）");
        BarChart<String, Number> bar = new BarChart<>(x, y);
        bar.setTitle("Top 10 类别支出");
        XYChart.Series<String, Number> series = new XYChart.Series<>();
        series.setName("支出");

        a.catStats.values().stream()
                .sorted(Comparator.comparingLong(s -> -Math.abs(s.expenseCents)))
                .limit(10)
                .forEach(s -> series.getData().add(
                        new XYChart.Data<>(s.category, centsToYuanDouble(Math.abs(s.expenseCents)))
                ));
        bar.getData().add(series);

        // 中部：横向并排（可拖动分隔）
        SplitPane charts = new SplitPane(new BorderPane(pie), new BorderPane(bar));
        charts.setPadding(new Insets(8, 12, 12, 12));
        charts.setDividerPositions(0.5);

        BorderPane root = new BorderPane();
        root.setTop(top);
        root.setCenter(charts);

        Tab t = new Tab("概览", root);
        t.setClosable(false);
        return t;
    }

    // === 明细（横向分栏：类别表 + 子类表，并联动筛选） ===
    private static Tab buildDetailsTab(Analysis a) {
        TableView<CatRow> catTable = new TableView<>();
        ObservableList<CatRow> catRows = toCatRows(a.catStats);
        catTable.setItems(catRows);
        catTable.getColumns().addAll(
                col("类别", CatRow::categoryProperty, 160),
                col("笔数", r -> r.count.asObject(), 80),
                col("收入(元)", r -> r.incomeYuan, 110),
                col("支出(元)", r -> r.expenseYuan, 110),
                col("净额(元)", r -> r.netYuan, 110),
                col("支出占比(全局)", r -> r.expenseSharePct, 120),
                col("平均支出(元)", r -> r.avgExpenseYuan, 120),
                col("最大支出(元)", r -> r.maxExpenseYuan, 120)
        );

        TableView<SubRow> subTable = new TableView<>();
        ObservableList<SubRow> allSubs = toSubRows(a.subStats);
        FilteredList<SubRow> subFiltered = new FilteredList<>(allSubs, r -> true);
        subTable.setItems(subFiltered);
        subTable.getColumns().addAll(
                col("类别", SubRow::categoryProperty, 160),
                col("子类别", SubRow::subCategoryProperty, 160),
                col("笔数", r -> r.count.asObject(), 80),
                col("收入(元)", r -> r.incomeYuan, 110),
                col("支出(元)", r -> r.expenseYuan, 110),
                col("净额(元)", r -> r.netYuan, 110),
                col("在本类别占比", r -> r.expenseSharePct, 120),
                col("平均支出(元)", r -> r.avgExpenseYuan, 120),
                col("最大支出(元)", r -> r.maxExpenseYuan, 120)
        );

        // 联动：点击左表的类别，右表只显示该类别的子类
        catTable.getSelectionModel().selectedItemProperty().addListener((obs, oldV, v) -> {
            String selCat = (v == null) ? null : v.category.get();
            subFiltered.setPredicate(sr -> selCat == null || selCat.equals(sr.category.get()));
        });
        if (!catRows.isEmpty()) catTable.getSelectionModel().selectFirst();

        SplitPane split = new SplitPane(new BorderPane(catTable), new BorderPane(subTable));
        split.setDividerPositions(0.45);

        Tab t = new Tab("明细", split);
        t.setClosable(false);
        return t;
    }

    // === 子类占比（选择类别 -> 该类别的子类占比饼图 + 表） ===
    private static Tab buildSubShareTab(Analysis a) {
        BorderPane root = new BorderPane();
        root.setPadding(new Insets(10));

        ComboBox<String> catSelect = new ComboBox<>();
        catSelect.getItems().addAll(a.catStats.keySet());
        catSelect.setEditable(false);
        if (!catSelect.getItems().isEmpty()) catSelect.getSelectionModel().selectFirst();

        PieChart pie = new PieChart();
        pie.setTitle("子类占比（按类别支出）");

        TableView<SubRow> table = new TableView<>();
        ObservableList<SubRow> allSubs = toSubRows(a.subStats);
        FilteredList<SubRow> subFiltered = new FilteredList<>(allSubs, r -> true);
        table.setItems(subFiltered);
        table.getColumns().addAll(
                col("子类别", SubRow::subCategoryProperty, 200),
                col("笔数", r -> r.count.asObject(), 80),
                col("支出(元)", r -> r.expenseYuan, 120),
                col("在本类别占比", r -> r.expenseSharePct, 140),
                col("平均支出(元)", r -> r.avgExpenseYuan, 140),
                col("最大支出(元)", r -> r.maxExpenseYuan, 140)
        );

        Runnable refresh = () -> {
            String c = catSelect.getSelectionModel().getSelectedItem();
            subFiltered.setPredicate(r -> c != null && c.equals(r.category.get()));
            pie.getData().clear();
            // 仅按支出项构建饼图（用绝对值）
            for (SubRow r : subFiltered) {
                // 解析“支出(元)”字符串到 double 仅用于展示（你也可以存 double）
                double absExpenseYuan = Math.abs(parseYuanString(r.expenseYuan.get()));
                if (absExpenseYuan > 0) {
                    pie.getData().add(new PieChart.Data(
                            r.subCategory.get() + "  " + r.expenseSharePct.get(),
                            absExpenseYuan
                    ));
                }
            }
        };
        catSelect.setOnAction(e -> refresh.run());
        refresh.run();

        // 上部工具条 + 中间横向并排（左饼图右表）
        ToolBar bar = new ToolBar(new Label("类别："), catSelect);
        SplitPane center = new SplitPane(new BorderPane(pie), new BorderPane(table));
        center.setDividerPositions(0.45);

        root.setTop(bar);
        root.setCenter(center);

        Tab t = new Tab("子类占比", root);
        t.setClosable(false);
        return t;
    }

    // === Rows 构造 ===
    private static ObservableList<CatRow> toCatRows(Map<String, Stat> map) {
        ObservableList<CatRow> list = FXCollections.observableArrayList();
        for (Stat s : map.values()) list.add(new CatRow(s));
        return list;
    }
    private static ObservableList<SubRow> toSubRows(Map<String, Map<String, Stat>> map) {
        ObservableList<SubRow> list = FXCollections.observableArrayList();
        for (Map.Entry<String, Map<String, Stat>> e : map.entrySet()) {
            for (Stat s : e.getValue().values()) list.add(new SubRow(s));
        }
        return list;
    }

    // === TableColumn 快速建 ===
    private static <S, T> TableColumn<S, T> col(String title, javafx.util.Callback<S, ObservableValue<T>> prop, int prefWidth) {
        TableColumn<S, T> c = new TableColumn<>(title);
        c.setCellValueFactory(cd -> prop.call(cd.getValue()));
        c.setPrefWidth(prefWidth);
        return c;
    }
    private static Label bold(String t) { Label l = new Label(t); l.setStyle("-fx-font-weight: bold;"); return l; }

    // === 数据模型 ===
    private static final class Analysis {
        final Map<String, Stat> catStats;
        final Map<String, Map<String, Stat>> subStats;
        final long totalIncome;     // 分
        final long totalExpenseAbs; // 分（绝对值）
        final long netCents;        // 分
        final long count;

        Analysis(Map<String, Stat> cat, Map<String, Map<String, Stat>> sub, long inc, long expAbs, long net, long count) {
            this.catStats = cat;
            this.subStats = sub;
            this.totalIncome = inc;
            this.totalExpenseAbs = expAbs;
            this.netCents = net;
            this.count = count;
        }
    }

    private static final class Stat {
        final String category;
        final String subCategory; // 可为 null（类别级）

        long count = 0;
        long incomeCents = 0;   // >0
        long expenseCents = 0;  // 累负
        long netCents = 0;      // income + expense
        long expenseCount = 0;
        long maxExpenseCents = 0;   // 最“负”的值（若无支出保持 0）
        long avgExpenseCents = 0;   // 负数（仅按支出计算）
        double expenseShare = 0d;   // 占比（类别：相对全局支出；子类：相对其类别支出）

        Stat(String category, String subCategory) {
            this.category = category;
            this.subCategory = subCategory;
        }
    }

    // === 表格 Row（类别） ===
    public static final class CatRow {
        private final StringProperty category = new SimpleStringProperty();
        private final LongProperty   count    = new SimpleLongProperty();
        private final StringProperty incomeYuan = new SimpleStringProperty();
        private final StringProperty expenseYuan= new SimpleStringProperty();
        private final StringProperty netYuan    = new SimpleStringProperty();
        private final StringProperty expenseSharePct = new SimpleStringProperty();
        private final StringProperty avgExpenseYuan  = new SimpleStringProperty();
        private final StringProperty maxExpenseYuan  = new SimpleStringProperty();

        CatRow(Stat s) {
            category.set(s.category);
            count.set(s.count);
            incomeYuan.set(fmtYuan(s.incomeCents));
            expenseYuan.set(fmtYuan(s.expenseCents));         // 为负显示负
            netYuan.set(fmtYuan(s.netCents));
            expenseSharePct.set(PCT_FMT.format(s.expenseShare));
            avgExpenseYuan.set(fmtYuan(s.avgExpenseCents));   // 为负显示负
            maxExpenseYuan.set(fmtYuan(s.maxExpenseCents));   // 为负显示负
        }
        public StringProperty categoryProperty() { return category; }
    }

    // === 表格 Row（子类别） ===
    public static final class SubRow {
        private final StringProperty category    = new SimpleStringProperty();
        private final StringProperty subCategory = new SimpleStringProperty();
        private final LongProperty   count       = new SimpleLongProperty();
        private final StringProperty incomeYuan  = new SimpleStringProperty();
        private final StringProperty expenseYuan = new SimpleStringProperty();
        private final StringProperty netYuan     = new SimpleStringProperty();
        private final StringProperty expenseSharePct = new SimpleStringProperty(); // 在所属类别内占比
        private final StringProperty avgExpenseYuan  = new SimpleStringProperty();
        private final StringProperty maxExpenseYuan  = new SimpleStringProperty();

        SubRow(Stat s) {
            category.set(s.category);
            subCategory.set(s.subCategory);
            count.set(s.count);
            incomeYuan.set(fmtYuan(s.incomeCents));
            expenseYuan.set(fmtYuan(s.expenseCents));
            netYuan.set(fmtYuan(s.netCents));
            expenseSharePct.set(PCT_FMT.format(s.expenseShare)); // 子类相对本类别
            avgExpenseYuan.set(fmtYuan(s.avgExpenseCents));
            maxExpenseYuan.set(fmtYuan(s.maxExpenseCents));
        }
        public StringProperty categoryProperty()    { return category; }
        public StringProperty subCategoryProperty() { return subCategory; }
    }

    // === 金额/比例格式化 ===
    private static String fmtYuan(long cents) {
        BigDecimal yuan = BigDecimal.valueOf(cents).movePointLeft(2);
        return YUAN_FMT.format(yuan);
    }
    private static double centsToYuanDouble(long centsAbs) {
        return BigDecimal.valueOf(centsAbs).movePointLeft(2).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
    private static String fmtPct(double v) { return PCT_FMT.format(v); }
    private static double parseYuanString(String s) {
        return new BigDecimal(s.replace(",", "")).doubleValue();
    }
}
