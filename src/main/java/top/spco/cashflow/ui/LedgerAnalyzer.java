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
package top.spco.cashflow.ui;

import javafx.beans.property.LongProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Callback;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import top.spco.cashflow.data.LedgerIO;
import top.spco.cashflow.data.MonthlyLedger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static top.spco.cashflow.util.POIUtil.*;

/**
 * 账本分析器：读取文件 -> 统计 -> 概览/明细/子类占比（含导出）
 */
public final class LedgerAnalyzer {

    private static final DecimalFormat YUAN_FMT = new DecimalFormat("#,##0.00");
    private static final DecimalFormat PCT_FMT = new DecimalFormat("0.00%");

    private LedgerAnalyzer() {
    }

    public static void showAnalysis(Stage owner, File file) throws IOException {
        LedgerIO.Bundle bundle = LedgerIO.load(file);
        Analysis a = analyzeBundle(bundle);

        // 三个视图
        SubShareView subShareView = buildSubShareView(a);
        Tab detailsTab = buildDetailsTab(a, catName -> {
            subShareView.selectCategory(catName);
            // 切换到“子类占比”页
            subShareView.tab.getTabPane().getSelectionModel().select(subShareView.tab);
        });
        Tab overviewTab = buildOverviewTab(a);

        TabPane tabs = new TabPane(overviewTab, detailsTab, subShareView.tab);

        // 窗口
        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.setTitle("分析 - " + file.getName());

        // 导出按钮（直接捕获上面的 stage 与 file）
        Button btnExport = new Button("导出为 Excel");
        btnExport.setOnAction(e -> {
            try {
                exportAnalysisToXlsx(stage, defaultExportName(file), a);
                new Alert(Alert.AlertType.INFORMATION, "导出完成").showAndWait();
            } catch (Exception ex) {
                new Alert(Alert.AlertType.ERROR, "导出失败：" + ex.getMessage()).showAndWait();
            }
        });

        ToolBar toolBar = new ToolBar(btnExport);

        BorderPane root = new BorderPane();
        root.setTop(toolBar);
        root.setCenter(tabs);

        stage.setScene(new Scene(root, 1200, 750));
        stage.show();
    }

    private static String defaultExportName(File file) {
        String name = file.getName();
        int i = name.lastIndexOf('.');
        if (i > 0) name = name.substring(0, i);
        return name + "-分析.xlsx";
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
            Stat ss = subStats.computeIfAbsent(cat, k -> new LinkedHashMap<>()).computeIfAbsent(sub, k -> new Stat(cat, sub));

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

            if (income) totalIncome += amount;
            else totalExpenseAbs += -amount;
            netCents += amount;
            count++;
        }

        // 占比（按支出）与平均支出（仅负数）
        for (Stat s : catStats.values()) {
            s.expenseShare = (totalExpenseAbs == 0) ? 0d : (Math.abs(s.expenseCents) * 1.0) / totalExpenseAbs;  // 类别在全局支出占比
            s.avgExpenseCents = avgHalfUp(s.expenseCents, s.expenseCount);
        }
        for (Map<String, Stat> m : subStats.values()) {
            long catExpenseAbs = 0L;
            for (Stat s : m.values()) catExpenseAbs += Math.abs(s.expenseCents);
            for (Stat s : m.values()) {
                s.expenseShare = (catExpenseAbs == 0) ? 0d : (Math.abs(s.expenseCents) * 1.0) / catExpenseAbs; // 子类在所属类别支出占比
                s.avgExpenseCents = avgHalfUp(s.expenseCents, s.expenseCount);
            }
        }

        return new Analysis(catStats, subStats, totalIncome, totalExpenseAbs, netCents, count);
    }

    private static long avgHalfUp(long sumCents, long count) {
        if (count == 0) return 0L;
        return BigDecimal.valueOf(sumCents)
                .divide(BigDecimal.valueOf(count), 0, RoundingMode.HALF_UP)
                .longValue();
    }

    // === 概览（饼图 + 柱图） ===
    private static Tab buildOverviewTab(Analysis a) {
        // 顶部概览数字
        GridPane top = new GridPane();
        top.setHgap(16);
        top.setVgap(6);
        top.setPadding(new Insets(12));
        top.addRow(0, bold("总收入（元）:"), new Label(fmtYuan(a.totalIncome)), bold("总支出（元）:"), new Label(fmtYuan(-a.totalExpenseAbs)), bold("净额（元）:"), new Label(fmtYuan(a.netCents)), bold("记录数:"), new Label(String.valueOf(a.count)));

        // 左：类别支出占比饼图（仅支出）
        PieChart pie = new PieChart();
        pie.setTitle("类别支出占比");
        for (Stat s : a.catStats.values()) {
            if (Math.abs(s.expenseCents) == 0) continue;
            pie.getData().add(new PieChart.Data(s.category + "  " + fmtPct(s.expenseShare), centsToYuanDouble(Math.abs(s.expenseCents))));
        }
        if (pie.getData().isEmpty()) {
            pie.setTitle("类别支出占比（无支出数据）");
        }

        // 右：Top 10 类别支出柱图 + “其它”
        CategoryAxis x = new CategoryAxis();
        NumberAxis y = new NumberAxis();
        y.setLabel("支出（元，绝对值）");
        BarChart<String, Number> bar = new BarChart<>(x, y);
        bar.setTitle("Top 10 类别支出");
        XYChart.Series<String, Number> series = new XYChart.Series<>();
        series.setName("支出");

        var sortedCats = a.catStats.values().stream().sorted(Comparator.comparingLong(s -> -Math.abs(s.expenseCents))).toList();
        sortedCats.stream().limit(10).forEach(s -> series.getData().add(new XYChart.Data<>(s.category, centsToYuanDouble(Math.abs(s.expenseCents)))));

        long others = sortedCats.stream().skip(10).mapToLong(s -> Math.abs(s.expenseCents)).sum();
        if (others > 0) {
            series.getData().add(new XYChart.Data<>("其它", centsToYuanDouble(others)));
        }
        bar.getData().add(series);

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

    // === 明细（左右：类别表 + 子类表；双击联动；导出） ===
    private static Tab buildDetailsTab(Analysis a, Consumer<String> onCategoryDoubleClick) {
        TableView<CatRow> catTable = new TableView<>();
        ObservableList<CatRow> catRows = toCatRows(a.catStats);
        catTable.setItems(catRows);
        catTable.getColumns().addAll(col("类别", CatRow::categoryProperty, 160), col("笔数", r -> r.count.asObject(), 80), rightNumCol("收入(元)", r -> r.incomeYuan, 110), rightNumCol("支出(元)", r -> r.expenseYuan, 110), rightNumCol("净额(元)", r -> r.netYuan, 110), rightNumCol("支出占比(全局)", r -> r.expenseSharePct, 140), rightNumCol("平均支出(元)", r -> r.avgExpenseYuan, 120), rightNumCol("最大支出(元)", r -> r.maxExpenseYuan, 120));

        TableView<SubRow> subTable = new TableView<>();
        ObservableList<SubRow> allSubs = toSubRows(a.subStats);
        FilteredList<SubRow> subFiltered = new FilteredList<>(allSubs, r -> true);
        subTable.setItems(subFiltered);
        subTable.getColumns().addAll(col("类别", SubRow::categoryProperty, 160), col("子类别", SubRow::subCategoryProperty, 160), col("笔数", r -> r.count.asObject(), 80), rightNumCol("收入(元)", r -> r.incomeYuan, 110), rightNumCol("支出(元)", r -> r.expenseYuan, 110), rightNumCol("净额(元)", r -> r.netYuan, 110), rightNumCol("在本类别占比", r -> r.expenseSharePct, 140), rightNumCol("平均支出(元)", r -> r.avgExpenseYuan, 120), rightNumCol("最大支出(元)", r -> r.maxExpenseYuan, 120));

        // 左表选择联动右表过滤
        catTable.getSelectionModel().selectedItemProperty().addListener((obs, oldV, v) -> {
            String selCat = (v == null) ? null : v.category.get();
            subFiltered.setPredicate(sr -> selCat == null || selCat.equals(sr.category.get()));
        });
        if (!catRows.isEmpty()) catTable.getSelectionModel().selectFirst();

        // 双击左表类别 -> 回调（通常切到“子类占比”并选中）
        catTable.setRowFactory(tv -> {
            TableRow<CatRow> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    String cat = row.getItem().category.get();
                    if (onCategoryDoubleClick != null) onCategoryDoubleClick.accept(cat);
                }
            });
            return row;
        });

        SplitPane split = new SplitPane(new BorderPane(catTable), new BorderPane(subTable));
        split.setDividerPositions(0.45);

        BorderPane root = new BorderPane();
        root.setCenter(split);

        Tab t = new Tab("明细", root);
        t.setClosable(false);
        return t;
    }

    // === 子类占比视图（含导出） ===
    private static SubShareView buildSubShareView(Analysis a) {
        return new SubShareView(a);
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
    private static <S, T> TableColumn<S, T> col(String title, Callback<S, ObservableValue<T>> prop, int prefWidth) {
        TableColumn<S, T> c = new TableColumn<>(title);
        c.setCellValueFactory(cd -> prop.call(cd.getValue()));
        c.setPrefWidth(prefWidth);
        return c;
    }

    private static <S> TableColumn<S, String> rightNumCol(String title, Callback<S, ObservableValue<String>> prop, int prefWidth) {
        TableColumn<S, String> c = col(title, prop, prefWidth);
        c.setStyle("-fx-alignment: CENTER-RIGHT;");
        c.setComparator(LedgerAnalyzer::compareNumericString);
        return c;
    }

    private static Label bold(String t) {
        Label l = new Label(t);
        l.setStyle("-fx-font-weight: bold;");
        return l;
    }

    // === 导出 ===
    private static void exportAnalysisToXlsx(Stage owner, String suggestedName, Analysis a) throws IOException {
        FileChooser fc = new FileChooser();
        fc.setInitialFileName(suggestedName.endsWith(".xlsx") ? suggestedName : suggestedName + ".xlsx");
        fc.getExtensionFilters().setAll(new FileChooser.ExtensionFilter("Excel 工作簿 (*.xlsx)", "*.xlsx"));
        File f = fc.showSaveDialog(owner);
        if (f == null) return;

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            DataFormat df = wb.createDataFormat();
            // 样式
            CellStyle hdr = wb.createCellStyle();
            var hdrFont = wb.createFont();
            hdrFont.setBold(true);
            hdr.setFont(hdrFont);

            CellStyle intStyle = wb.createCellStyle();
            intStyle.setDataFormat(df.getFormat("0"));

            CellStyle money = wb.createCellStyle();
            money.setDataFormat(df.getFormat("¥#,##0.00;[Red]-¥#,##0.00"));

            CellStyle pct = wb.createCellStyle();
            pct.setDataFormat(df.getFormat("0.00%"));

            // Sheet 1：概览
            XSSFSheet sOverview = wb.createSheet("概览");
            int r = 0;
            r = writeKV(sOverview, r, "总收入（元）", centsToYuanDouble(a.totalIncome), money, hdr);
            r = writeKV(sOverview, r, "总支出（元）", centsToYuanDouble(-a.totalExpenseAbs), money, hdr);
            r = writeKV(sOverview, r, "净额（元）", centsToYuanDouble(a.netCents), money, hdr);
            r = writeKV(sOverview, r, "记录数", a.count, intStyle, hdr);
            autoSize(sOverview, 0, 1);

            // Sheet 2：类别明细
            XSSFSheet sCat = wb.createSheet("类别明细");
            int row = 0;
            String[] catHeaders = {"类别", "笔数", "收入(元)", "支出(元)", "净额(元)", "支出占比(全局)", "平均支出(元)", "最大支出(元)"};
            writeHeader(sCat, row++, catHeaders, hdr);

            for (Stat s : a.catStats.values()) {
                Row rr = sCat.createRow(row++);
                int c = 0;
                cell(rr, c++).setCellValue(s.category);
                num(rr, c++, s.count, intStyle);
                num(rr, c++, centsToYuanDouble(s.incomeCents), money);
                num(rr, c++, centsToYuanDouble(s.expenseCents), money);
                num(rr, c++, centsToYuanDouble(s.netCents), money);
                num(rr, c++, s.expenseShare, pct);
                num(rr, c++, centsToYuanDouble(s.avgExpenseCents), money);
                num(rr, c++, centsToYuanDouble(s.maxExpenseCents), money);
            }
            autoSize(sCat, 0, catHeaders.length - 1);

            // Sheet 3：子类明细
            XSSFSheet sSub = wb.createSheet("子类明细");
            row = 0;
            String[] subHeaders = {"类别", "子类别", "笔数", "收入(元)", "支出(元)", "净额(元)", "在本类别占比", "平均支出(元)", "最大支出(元)"};
            writeHeader(sSub, row++, subHeaders, hdr);

            for (Map.Entry<String, Map<String, Stat>> e : a.subStats.entrySet()) {
                for (Stat s : e.getValue().values()) {
                    Row rr = sSub.createRow(row++);
                    int c = 0;
                    cell(rr, c++).setCellValue(s.category);
                    cell(rr, c++).setCellValue(s.subCategory);
                    num(rr, c++, s.count, intStyle);
                    num(rr, c++, centsToYuanDouble(s.incomeCents), money);
                    num(rr, c++, centsToYuanDouble(s.expenseCents), money);
                    num(rr, c++, centsToYuanDouble(s.netCents), money);
                    num(rr, c++, s.expenseShare, pct);
                    num(rr, c++, centsToYuanDouble(s.avgExpenseCents), money);
                    num(rr, c++, centsToYuanDouble(s.maxExpenseCents), money);
                }
            }
            autoSize(sSub, 0, subHeaders.length - 1);

            try (FileOutputStream out = new FileOutputStream(f)) {
                wb.write(out);
            }
        }
    }

    /**
     * @param totalIncome     分
     * @param totalExpenseAbs 分（绝对值）
     * @param netCents        分
     */
    private record Analysis(Map<String, Stat> catStats, Map<String, Map<String, Stat>> subStats, long totalIncome,
                            long totalExpenseAbs, long netCents, long count) {
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
        private final LongProperty count = new SimpleLongProperty();
        private final StringProperty incomeYuan = new SimpleStringProperty();
        private final StringProperty expenseYuan = new SimpleStringProperty();
        private final StringProperty netYuan = new SimpleStringProperty();
        private final StringProperty expenseSharePct = new SimpleStringProperty();
        private final StringProperty avgExpenseYuan = new SimpleStringProperty();
        private final StringProperty maxExpenseYuan = new SimpleStringProperty();

        CatRow(Stat s) {
            category.set(s.category);
            count.set(s.count);
            incomeYuan.set(fmtYuan(s.incomeCents));
            expenseYuan.set(fmtYuan(s.expenseCents));         // 负数显示为负
            netYuan.set(fmtYuan(s.netCents));
            expenseSharePct.set(PCT_FMT.format(s.expenseShare));
            avgExpenseYuan.set(fmtYuan(s.avgExpenseCents));   // 负数显示为负
            maxExpenseYuan.set(fmtYuan(s.maxExpenseCents));   // 负数显示为负
        }

        public StringProperty categoryProperty() {
            return category;
        }
    }

    // === 表格 Row（子类别） ===
    public static final class SubRow {
        private final StringProperty category = new SimpleStringProperty();
        private final StringProperty subCategory = new SimpleStringProperty();
        private final LongProperty count = new SimpleLongProperty();
        private final StringProperty incomeYuan = new SimpleStringProperty();
        private final StringProperty expenseYuan = new SimpleStringProperty();
        private final StringProperty netYuan = new SimpleStringProperty();
        private final StringProperty expenseSharePct = new SimpleStringProperty(); // 在所属类别内占比
        private final StringProperty avgExpenseYuan = new SimpleStringProperty();
        private final StringProperty maxExpenseYuan = new SimpleStringProperty();

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

        public StringProperty categoryProperty() {
            return category;
        }

        public StringProperty subCategoryProperty() {
            return subCategory;
        }
    }

    // === 子视图：子类占比 ===
    private static final class SubShareView {
        final Tab tab;
        private final ComboBox<String> catSelect = new ComboBox<>();
        private final PieChart pie = new PieChart();
        private final TableView<SubRow> table = new TableView<>();
        private final FilteredList<SubRow> subFiltered;

        SubShareView(Analysis a) {
            BorderPane root = new BorderPane();
            root.setPadding(new Insets(10));

            // 顶部下拉：选择类别
            catSelect.getItems().addAll(a.catStats.keySet());
            catSelect.setEditable(false);
            catSelect.setPrefWidth(260);

            // 顶部工具栏
            ToolBar bar = new ToolBar(new Label("类别："), catSelect);
            root.setTop(bar);

            // 饼图标题
            pie.setTitle("子类占比（按类别支出）");

            // 表格数据
            ObservableList<SubRow> allSubs = toSubRows(a.subStats);
            subFiltered = new FilteredList<>(allSubs, r -> true);
            table.setItems(subFiltered);
            table.getColumns().addAll(
                    col("子类别", SubRow::subCategoryProperty, 200),
                    col("笔数", r -> r.count.asObject(), 80),
                    rightNumCol("支出(元)", r -> r.expenseYuan, 120),
                    rightNumCol("在本类别占比", r -> r.expenseSharePct, 140),
                    rightNumCol("平均支出(元)", r -> r.avgExpenseYuan, 140),
                    rightNumCol("最大支出(元)", r -> r.maxExpenseYuan, 140)
            );

            // 切换类别 -> 刷新
            catSelect.setOnAction(e -> refresh());

            // 初始选择并刷新
            if (!catSelect.getItems().isEmpty()) {
                catSelect.getSelectionModel().selectFirst();
            }
            refresh();

            // 中间布局：左饼图右表格
            SplitPane center = new SplitPane(new BorderPane(pie), new BorderPane(table));
            center.setDividerPositions(0.45);
            root.setCenter(center);

            this.tab = new Tab("子类占比", root);
            this.tab.setClosable(false);
        }

        // 对外联动：从“明细”页双击类别时调用
        void selectCategory(String cat) {
            if (cat == null) return;
            if (catSelect.getItems().contains(cat)) {
                catSelect.getSelectionModel().select(cat);
                refresh();
            }
        }

        // 根据当前选择的类别刷新饼图与表格过滤
        private void refresh() {
            String c = catSelect.getSelectionModel().getSelectedItem();
            subFiltered.setPredicate(r -> c != null && c.equals(r.category.get()));

            pie.getData().clear();
            for (SubRow r : subFiltered) {
                double absExpenseYuan = toBigDecimal(r.expenseYuan.get()).abs().doubleValue();
                if (absExpenseYuan > 0) {
                    pie.getData().add(new PieChart.Data(
                            r.subCategory.get() + "  " + r.expenseSharePct.get(),
                            absExpenseYuan
                    ));
                }
            }
            if (c == null) {
                pie.setTitle("子类占比（请选择类别）");
            } else if (pie.getData().isEmpty()) {
                pie.setTitle("子类占比（该类别无支出）");
            } else {
                pie.setTitle("子类占比（按类别支出）");
            }
        }
    }

    // === 金额/比例格式化 ===
    private static String fmtYuan(long cents) {
        BigDecimal yuan = BigDecimal.valueOf(cents).movePointLeft(2);
        return YUAN_FMT.format(yuan);
    }

    private static double centsToYuanDouble(long centsAbs) {
        return BigDecimal.valueOf(centsAbs).movePointLeft(2).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static String fmtPct(double v) {
        return PCT_FMT.format(v);
    }

    // === 列排序用：把字符串金额/百分比转为 BigDecimal 比较 ===
    private static int compareNumericString(String a, String b) {
        return toBigDecimal(a).compareTo(toBigDecimal(b));
    }

    private static BigDecimal toBigDecimal(String s) {
        if (s == null || s.isBlank()) return BigDecimal.ZERO;
        String t = s.replace(",", "").replace("%", "").trim();
        try {
            return new BigDecimal(t);
        } catch (Exception ignore) {
            return BigDecimal.ZERO;
        }
    }
}