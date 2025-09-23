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

import javafx.beans.property.LongProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.GridPane;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.StringConverter;
import top.spco.cashflow.data.LedgerIO;
import top.spco.cashflow.data.LedgerIO.CategoryTaxonomy;
import top.spco.cashflow.data.MonthlyLedger;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 主控制器（分类树为真相源，删除/修改记录不会丢失下拉选项）
 */
public class MainController {
    private static final String FILE_EXT = ".cflg";
    private static final String FILE_DESC = "Cashflow Ledger (*" + FILE_EXT + ")";

    // ===== FXML =====
    @FXML
    private TableView<RecordRow> tableView;
    @FXML
    private TableColumn<RecordRow, Long> colTimestamp;   // 存 long，显示“日期时间”
    @FXML
    private TableColumn<RecordRow, Long> colAmount;      // 存“分”，显示“元”
    @FXML
    private TableColumn<RecordRow, String> colCategory;
    @FXML
    private TableColumn<RecordRow, String> colSubCategory;
    @FXML
    private TableColumn<RecordRow, String> colNote;
    @FXML
    private TextField searchField;
    @FXML
    private ComboBox<Integer> yearBox;
    @FXML
    private ComboBox<Integer> monthBox;

    // 记住“快速添加”里用户输入的日（不清空）
    private Integer lastQuickDay = null;

    // 避免监听器相互触发
    private boolean updatingYmPickers = false;

    // ===== 状态 =====
    private final ObservableList<RecordRow> master = FXCollections.observableArrayList();
    private FilteredList<RecordRow> filtered;
    private SortedList<RecordRow> sorted;

    private YearMonth currentYm = YearMonth.now();
    private File currentFile = null;

    /**
     * 分类树真相源（文件打开时载入；新增/编辑时并入；删除/修改记录不影响它）
     */
    private CategoryTaxonomy taxonomy = new CategoryTaxonomy(new ArrayList<>(), new ArrayList<>()); // 空起步

    // 下拉候选（由 taxonomy 派生）
    private final ObservableList<String> categoryChoices = FXCollections.observableArrayList();
    private final Map<String, ObservableList<String>> subChoicesByCat = new HashMap<>();

    // ===== 初始化 =====
    @FXML
    public void initialize() {
        // 列绑定
        colTimestamp.setCellValueFactory(cd -> cd.getValue().timestampMsProperty().asObject());
        colAmount.setCellValueFactory(cd -> cd.getValue().amountCentsProperty().asObject());
        colCategory.setCellValueFactory(cd -> cd.getValue().categoryProperty());
        colSubCategory.setCellValueFactory(cd -> cd.getValue().subCategoryProperty());
        colNote.setCellValueFactory(cd -> cd.getValue().noteProperty());

        // 时间戳：保持 Long 排序，单元格显示为人类可读日期
        colTimestamp.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Long epochMs, boolean empty) {
                super.updateItem(epochMs, empty);
                if (empty || epochMs == null) {
                    setText(null);
                    return;
                }
                setText(formatDateTime(epochMs));
            }
        });

        // 金额：显示元；编辑时用元输入，内部存“分”
        colAmount.setCellFactory(col -> new YuanEditCell());
        tableView.setEditable(true);

        // 过滤 + 排序
        filtered = new FilteredList<>(master, r -> true);
        sorted = new SortedList<>(filtered);
        sorted.comparatorProperty().bind(tableView.comparatorProperty());
        tableView.setItems(sorted);

        // 默认按时间戳升序
        colTimestamp.setSortType(TableColumn.SortType.ASCENDING);
        tableView.getSortOrder().add(colTimestamp);

        // 首次根据空 taxonomy 重建一次（空列表）
        rebuildChoicesFromTaxonomy(taxonomy);

        initYearMonthPickers();         // 初始化年/月控件
        syncYearMonthPickersFromState(); // 与 currentYm 同步显示
    }

    // ===== 文件操作（分类树结构） =====
    @FXML
    private void onAnalyzeFile() {
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().setAll(
                new FileChooser.ExtensionFilter("Cashflow Ledger (*.cflg)", "*.cflg"),
                new FileChooser.ExtensionFilter("所有文件 (*.*)", "*.*")
        );
        File f = fc.showOpenDialog(tableView.getScene().getWindow());
        if (f == null) return;
        try {
            LedgerAnalyzer.showAnalysis((Stage) tableView.getScene().getWindow(), f);
        } catch (IOException ex) {
            showError("分析失败：" + ex.getMessage());
        }
    }

    // 直接分析当前已打开文件（若你维护了 currentFile）
    @FXML
    private void onAnalyze() {
        if (currentFile == null) {
            showError("请先打开一个账本文件");
            return;
        }
        try {
            LedgerAnalyzer.showAnalysis((Stage) tableView.getScene().getWindow(), currentFile);
        } catch (IOException ex) {
            showError("分析失败：" + ex.getMessage());
        }
    }

    @FXML
    private void onOpen() {
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().setAll(
                new FileChooser.ExtensionFilter(FILE_DESC, "*" + FILE_EXT),
                new FileChooser.ExtensionFilter("所有文件 (*.*)", "*.*")
        );
        File f = fc.showOpenDialog(getStage());
        if (f == null) return;
        try {
            LedgerIO.Bundle b = LedgerIO.load(f);
            currentYm = YearMonth.of(b.ledger.year(), b.ledger.month());
            syncYearMonthPickersFromState();
            currentFile = f;

            master.clear();
            int[] order = b.ledger.sortedIndicesByTimestampAsc();
            for (int r : order) {
                MonthlyLedger.EntryView e = b.ledger.get(r);
                String cat = b.taxonomy.categoryName(e.categoryId());
                String sub = b.taxonomy.subName(e.categoryId(), e.subCategoryId());
                master.add(new RecordRow(e.timestamp(), e.amountInCents(), cat, sub, e.noteUtf8()));
            }

            // ★ 用文件中的 taxonomy 作为真相源
            this.taxonomy = b.taxonomy;
            rebuildChoicesFromTaxonomy(this.taxonomy);
            tableView.sort();
            showInfo("已打开：" + f.getName());
        } catch (IOException ex) {
            showError("读取失败：" + ex.getMessage());
        }
    }

    @FXML
    private void onSave() {
        if (currentFile == null) {
            FileChooser fc = new FileChooser();
            fc.getExtensionFilters().setAll(new FileChooser.ExtensionFilter(FILE_DESC, "*" + FILE_EXT));
            fc.setInitialFileName(defaultFileName());
            File chosen = fc.showSaveDialog(getStage());
            if (chosen == null) return;
            currentFile = ensureExt(chosen); // ★ 确保是 .cflg
        }
        try {
            // ★ 若 taxonomy 为空（如新文件），兜底从当前行构建一次
            if (taxonomy == null || taxonomy.categoryCount() == 0) {
                taxonomy = buildTaxonomyFromRows(master);
            }

            // ★ 保险：把所有行中出现的 cat/sub 并入 taxonomy（理论上编辑时已并入）
            for (RecordRow r : master) ensureInTaxonomy(r.getCategory(), r.getSubCategory());

            // 约束检查
            if (taxonomy.categoryCount() == 0) throw new IllegalStateException("文件必须至少包含一个类别");
            for (int c = 0; c < taxonomy.categoryCount(); c++) {
                if (taxonomy.subCount(c) == 0)
                    throw new IllegalStateException("类别【" + taxonomy.categoryName(c) + "】必须至少包含一个子类别");
            }

            // 写回 Ledger
            MonthlyLedger ledger = MonthlyLedger.of(currentYm, Math.max(32, master.size()), Math.max(256, master.size() * 16));
            for (RecordRow r : master) {
                int catId = taxonomy.categoryIdOf(r.getCategory());
                int subId = taxonomy.subIdOf(catId, r.getSubCategory());
                ledger.add(r.getTimestampMs(), r.getAmountCents(), catId, subId, r.getNote());
            }
            LedgerIO.save(ledger, taxonomy, currentFile);
            showInfo("已保存：" + currentFile.getName());
        } catch (Exception ex) {
            showError("保存失败：" + ex.getMessage());
        }
    }

    @FXML
    private void onExit() {
        getStage().close();
    }

    // ===== CRUD =====
    @FXML
    private void onAdd() {
        RecordRow edited = showEditDialog(null);
        if (edited != null) {
            master.add(edited);
            rebuildChoicesFromTaxonomy(taxonomy);
            tableView.sort();
        }
    }

    @FXML
    private void onQuickAdd() {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("快速添加");
        ButtonType ADD_NEXT = new ButtonType("添加下一条", ButtonBar.ButtonData.APPLY);
        dlg.getDialogPane().getButtonTypes().addAll(ADD_NEXT, ButtonType.CLOSE);

        // 固定显示当前账本的“年-月”
        Label ymLabel = new Label(currentYm.toString()); // 例如 2010-02

        // “日”输入：默认上次用过的日；否则默认今天（限制在当月范围内）
        int defDay = (lastQuickDay != null) ? lastQuickDay
                : Math.min(LocalDate.now().getDayOfMonth(), currentYm.lengthOfMonth());
        TextField dayField = new TextField(String.valueOf(defDay));

        TextField amtField = new TextField();
        ComboBox<String> catBox = new ComboBox<>(categoryChoices);
        catBox.setEditable(true);
        catBox.setConverter(PlainStringConverter.INSTANCE);
        ComboBox<String> subBox = new ComboBox<>();
        subBox.setEditable(true);
        subBox.setConverter(PlainStringConverter.INSTANCE);
        refreshSubChoices(catBox, subBox, "");

        // 类别变化 -> 子类联动
        catBox.valueProperty().addListener((o, ov, nv) -> refreshSubChoices(catBox, subBox, ""));
        catBox.getEditor().textProperty().addListener((o, ov, nv) -> refreshSubChoices(catBox, subBox, ""));

        TextField noteField = new TextField();
        Button importBtn = new Button("从微信支付导入…");
        importBtn.setOnAction(e -> onImportWeChatPay());

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.addRow(0, new Label("账本年月"), ymLabel);
        grid.addRow(1, new Label("日"), dayField);
        grid.addRow(2, new Label("金额(元)"), amtField);
        grid.addRow(3, new Label("类别(必选)"), catBox);
        grid.addRow(4, new Label("子类别(必选)"), subBox);
        grid.addRow(5, new Label("备注"), noteField);
        grid.add(importBtn, 1, 6);
        dlg.getDialogPane().setContent(grid);

        Button addBtn = (Button) dlg.getDialogPane().lookupButton(ADD_NEXT);
        addBtn.addEventFilter(javafx.event.ActionEvent.ACTION, evt -> {
            try {
                int day = Integer.parseInt(dayField.getText().trim());
                validateDayInMonth(currentYm, day); // 校验“日”在当月合法

                long cents = parseQuickAmountToCents(amtField.getText().trim()); // 默认支出（无正负号）
                String cat = trimToEmpty(getComboText(catBox));
                String sub = trimToEmpty(getComboText(subBox));
                String note = Optional.ofNullable(noteField.getText()).orElse("").trim();

                if (cat.isEmpty()) throw new IllegalArgumentException("类别不能为空");
                if (sub.isEmpty()) throw new IllegalArgumentException("子类别不能为空");

                // 生成时间戳：使用“账本年月 + 日 + 当前时间的时分秒”
                long ts = epochMsOf(currentYm, day, LocalTime.now());

                ensureInTaxonomy(cat, sub);
                rebuildChoicesFromTaxonomy(taxonomy);

                master.add(new RecordRow(ts, cents, cat, sub, note));
                tableView.sort();

                // 记住“日”，不清空；金额与备注清空；焦点回金额并全选
                lastQuickDay = day;
                amtField.clear();
                noteField.clear();
                amtField.requestFocus();
                amtField.selectAll();

                evt.consume(); // 不关闭对话框
            } catch (Exception ex) {
                evt.consume();
                showError(ex.getMessage());
            }
        });

        // 回车 = 添加下一条
        amtField.setOnAction(e -> addBtn.fire());

        // 初始焦点：金额
        dlg.setOnShown(e -> {
            amtField.requestFocus();
            amtField.selectAll();
        });

        dlg.showAndWait(); // Close 关闭
    }

    /**
     * 解析“快速添加”的金额文本为分。
     * 规则：未以'+'或'-'开头 -> 视为支出（负数）。支持 "+12.34" / "-12.34" / "12.34"。
     */
    private long parseQuickAmountToCents(String yuanText) {
        String t = Optional.ofNullable(yuanText).orElse("").trim();
        if (t.isEmpty()) throw new IllegalArgumentException("金额不能为空");
        int sign;
        if (t.startsWith("+")) {
            sign = +1;
            t = t.substring(1).trim();
        } else if (t.startsWith("-")) {
            sign = -1;
            t = t.substring(1).trim();
        } else {
            sign = -1;
        } // 默认支出
        long abs = yuanToCents(t); // 复用你已有的方法
        return sign * abs;
    }

    /**
     * 从微信支付导入：这里只给入口与文件选择，你来实现解析并批量添加到 master。
     */
    @FXML
    private void onImportWeChatPay() {
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV 或 明细文件", "*.csv", "*.txt", "*.*"));
        File f = fc.showOpenDialog(getStage());
        if (f == null) return;
        try {
            importWeChatPay(f); // ★ 见下一个空实现，留给你填解析与映射逻辑
            // 成功后刷新可选项 & 排序
            rebuildChoicesFromTaxonomy(taxonomy);
            tableView.sort();
            showInfo("导入完成");
        } catch (Exception ex) {
            showError("导入失败：" + ex.getMessage());
        }
    }

    /**
     * 占位：把解析结果转成 RecordRow 并入 master，同时调用 ensureInTaxonomy(cat, sub)
     */
    private void importWeChatPay(File file) throws Exception {
        // TODO: 你来实现解析，例如：
        // List<WeChatRecord> list = WeChatParser.parse(file);
        // for (WeChatRecord r : list) {
        //     long ts = r.epochMillis();
        //     long cents = r.isIncome() ? yuanToCents(r.amountYuan()) : -yuanToCents(r.amountYuan());
        //     String cat = r.category();
        //     String sub = r.subCategory();
        //     String note = r.note();
        //     ensureInTaxonomy(cat, sub);
        //     master.add(new RecordRow(ts, cents, cat, sub, note));
        // }
    }

    @FXML
    private void onEdit() {
        RecordRow sel = tableView.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        RecordRow edited = showEditDialog(sel);
        if (edited != null) {
            int idx = master.indexOf(sel);
            master.set(idx, edited);
            rebuildChoicesFromTaxonomy(taxonomy); // ★ 不从行反推
            tableView.sort();
        }
    }

    @FXML
    private void onDelete() {
        int idx = tableView.getSelectionModel().getSelectedIndex();
        if (idx < 0) return;
        RecordRow removed = sorted.get(idx);
        master.remove(removed);
        rebuildChoicesFromTaxonomy(taxonomy);     // ★ 删除记录不影响 taxonomy
    }

    @FXML
    public void onEditCategory() {
        // 1) 基于当前 taxonomy 构造可编辑副本
        ObservableList<String> catList = FXCollections.observableArrayList(taxonomy.categories());
        List<ObservableList<String>> subLists = new ArrayList<>();
        for (String c : catList) {
            subLists.add(FXCollections.observableArrayList(taxonomy.subsOf(c)));
        }

        // 2) UI：类别/子类别列表 + 操作按钮
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("编辑分类");
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dlg.setResultConverter(bt -> bt); // ★ 关键：把点击的按钮类型作为结果返回

        ListView<String> lvCat = new ListView<>(catList);
        lvCat.setPrefWidth(220);
        ListView<String> lvSub = new ListView<>();
        lvSub.setPrefWidth(220);

        // 选中类别时刷新子类列表
        lvCat.getSelectionModel().selectedIndexProperty().addListener((obs, oldI, newI) -> {
            int i = newI == null ? -1 : newI.intValue();
            if (i >= 0 && i < subLists.size()) {
                lvSub.setItems(subLists.get(i));
            } else {
                lvSub.setItems(FXCollections.observableArrayList());
            }
        });
        if (!catList.isEmpty()) lvCat.getSelectionModel().select(0);

        // 按钮：类别
        Button btnAddCat = new Button("新增类别");
        Button btnRenCat = new Button("重命名类别");
        Button btnDelCat = new Button("删除类别");

        // 按钮：子类
        Button btnAddSub = new Button("新增子类");
        Button btnRenSub = new Button("重命名子类");
        Button btnDelSub = new Button("删除子类");

        // 3) 类别新增（要求立刻给一个子类）
        btnAddCat.setOnAction(e -> {
            String cat = promptText("新增类别", "输入类别名称：", "");
            if (cat == null) return;
            cat = cat.trim();
            if (cat.isEmpty()) {
                showError("类别名称不能为空");
                return;
            }
            if (catList.contains(cat)) {
                showError("类别已存在");
                return;
            }

            String sub = promptText("新增子类", "为该类别添加至少一个子类：", "");
            if (sub == null) return;
            sub = sub.trim();
            if (sub.isEmpty()) {
                showError("子类名称不能为空");
                return;
            }

            catList.add(cat);
            subLists.add(FXCollections.observableArrayList(sub));
            lvCat.getSelectionModel().select(catList.size() - 1);
        });

        // 4) 类别重命名（同步更新行）
        btnRenCat.setOnAction(e -> {
            int i = lvCat.getSelectionModel().getSelectedIndex();
            if (i < 0) {
                showError("请先选择一个类别");
                return;
            }
            String oldName = catList.get(i);
            String newName = promptText("重命名类别", "输入新的类别名称：", oldName);
            if (newName == null) return;
            newName = newName.trim();
            if (newName.isEmpty()) {
                showError("类别名称不能为空");
                return;
            }
            if (catList.contains(newName) && !oldName.equals(newName)) {
                showError("类别已存在");
                return;
            }
            if (!oldName.equals(newName)) {
                catList.set(i, newName);                     // 更新分类名
                updateRowsForCategoryRename(oldName, newName); // 行内同步
            }
        });

        // 5) 类别删除（被引用/最后一个禁止）
        btnDelCat.setOnAction(e -> {
            int i = lvCat.getSelectionModel().getSelectedIndex();
            if (i < 0) {
                showError("请先选择一个类别");
                return;
            }
            if (catList.size() <= 1) {
                showError("至少需要保留一个类别，不能删除最后一个");
                return;
            }
            String cat = catList.get(i);
            if (isCategoryUsed(cat)) {
                showError("类别【" + cat + "】被记录引用，不能删除");
                return;
            }
            catList.remove(i);
            subLists.remove(i);
            if (!catList.isEmpty()) lvCat.getSelectionModel().select(Math.min(i, catList.size() - 1));
        });

        // 6) 子类新增
        btnAddSub.setOnAction(e -> {
            int i = lvCat.getSelectionModel().getSelectedIndex();
            if (i < 0) {
                showError("请先选择一个类别");
                return;
            }
            ObservableList<String> subs = subLists.get(i);
            String sub = promptText("新增子类", "输入子类名称：", "");
            if (sub == null) return;
            sub = sub.trim();
            if (sub.isEmpty()) {
                showError("子类名称不能为空");
                return;
            }
            if (subs.contains(sub)) {
                showError("子类已存在");
                return;
            }
            subs.add(sub);
            lvSub.getSelectionModel().select(subs.size() - 1);
        });

        // 7) 子类重命名（同步更新行）
        btnRenSub.setOnAction(e -> {
            int i = lvCat.getSelectionModel().getSelectedIndex();
            int j = lvSub.getSelectionModel().getSelectedIndex();
            if (i < 0) {
                showError("请先选择一个类别");
                return;
            }
            if (j < 0) {
                showError("请先选择一个子类");
                return;
            }
            String cat = catList.get(i);
            ObservableList<String> subs = subLists.get(i);
            String oldSub = subs.get(j);
            String newSub = promptText("重命名子类", "输入新的子类名称：", oldSub);
            if (newSub == null) return;
            newSub = newSub.trim();
            if (newSub.isEmpty()) {
                showError("子类名称不能为空");
                return;
            }
            if (subs.contains(newSub) && !oldSub.equals(newSub)) {
                showError("子类已存在");
                return;
            }
            if (!oldSub.equals(newSub)) {
                subs.set(j, newSub);
                updateRowsForSubRename(cat, oldSub, newSub); // 行内同步
            }
        });

        // 8) 子类删除（被引用/最后一个禁止）
        btnDelSub.setOnAction(e -> {
            int i = lvCat.getSelectionModel().getSelectedIndex();
            int j = lvSub.getSelectionModel().getSelectedIndex();
            if (i < 0) {
                showError("请先选择一个类别");
                return;
            }
            if (j < 0) {
                showError("请先选择一个子类");
                return;
            }
            ObservableList<String> subs = subLists.get(i);
            if (subs.size() <= 1) {
                showError("每个类别至少需要一个子类，不能删除最后一个");
                return;
            }
            String cat = catList.get(i);
            String sub = subs.get(j);
            if (isSubUsed(cat, sub)) {
                showError("【" + cat + " / " + sub + "】被记录引用，不能删除");
                return;
            }
            subs.remove(j);
            if (!subs.isEmpty()) lvSub.getSelectionModel().select(Math.min(j, subs.size() - 1));
        });

        // 布局
        ToolBar catBar = new ToolBar(btnAddCat, btnRenCat, btnDelCat);
        ToolBar subBar = new ToolBar(btnAddSub, btnRenSub, btnDelSub);

        GridPane gp = new GridPane();
        gp.setHgap(12);
        gp.setVgap(8);
        gp.addRow(0, new Label("类别"), new Label("子类别"));
        gp.addRow(1, lvCat, lvSub);
        gp.addRow(2, catBar, subBar);
        dlg.getDialogPane().setContent(gp);

        // OK 之前校验：至少 1 个类别、且每个类别至少 1 个子类
        Button okBtn = (Button) dlg.getDialogPane().lookupButton(ButtonType.OK);
        okBtn.addEventFilter(javafx.event.ActionEvent.ACTION, evt -> {
            if (catList.isEmpty()) {
                evt.consume();
                showError("至少需要一个类别");
                return;
            }
            for (int i = 0; i < catList.size(); i++) {
                if (subLists.get(i).isEmpty()) {
                    evt.consume();
                    showError("类别【" + catList.get(i) + "】缺少子类");
                    return;
                }
            }
        });

        // 9) 提交：只有按了 OK 才真正写回 taxonomy
        Optional<ButtonType> res = dlg.showAndWait();
        if (res.isPresent() && res.get() == ButtonType.OK) {
            List<String> catsFinal = new ArrayList<>(catList);
            List<List<String>> subsFinal = new ArrayList<>();
            for (ObservableList<String> ol : subLists) subsFinal.add(new ArrayList<>(ol));
            this.taxonomy = new CategoryTaxonomy(catsFinal, subsFinal); // ★ 真正写回
            rebuildChoicesFromTaxonomy(this.taxonomy);                  // ★ 刷新下拉
            tableView.refresh();
        }
    }

    // PAGE - 1
    /* ======= 下方是本方法用到的小工具 ======= */

    private static void validateDayInMonth(YearMonth ym, int day) {
        int max = ym.lengthOfMonth();
        if (day < 1 || day > max)
            throw new IllegalArgumentException("非法日：" + day + "。该月应为 1.." + max);
    }

    /**
     * 由“账本年月 + 日 + 指定时刻”生成本地时区的时间戳（毫秒）
     */
    private static long epochMsOf(YearMonth ym, int day, LocalTime time) {
        return ym.atDay(day).atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private void initYearMonthPickers() {
        // 年份：当前年±10
        int y = currentYm.getYear();
        ObservableList<Integer> years = FXCollections.observableArrayList();
        for (int i = y - 10; i <= y + 10; i++) years.add(i);
        yearBox.setItems(years);

        // 月份：1..12
        monthBox.setItems(FXCollections.observableArrayList(
                1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12
        ));

        // 监听修改 -> 更新 currentYm
        yearBox.valueProperty().addListener((o, ov, nv) -> applyYearMonthFromPickers());
        monthBox.valueProperty().addListener((o, ov, nv) -> applyYearMonthFromPickers());
    }

    private void syncYearMonthPickersFromState() {
        updatingYmPickers = true;
        try {
            int y = currentYm.getYear();
            int m = currentYm.getMonthValue();

            // 若年份不在列表里，补上（比如打开了很旧/很新的文件）
            if (!yearBox.getItems().contains(y)) {
                ObservableList<Integer> ys = yearBox.getItems();
                ys.add(y);
                FXCollections.sort(ys);
            }
            yearBox.setValue(y);
            monthBox.setValue(m);
        } finally {
            updatingYmPickers = false;
        }
    }

    private void applyYearMonthFromPickers() {
        if (updatingYmPickers) return;
        Integer y = yearBox.getValue();
        Integer m = monthBox.getValue();
        if (y == null || m == null) return;
        try {
            YearMonth newYm = YearMonth.of(y, m);
            if (!Objects.equals(newYm, currentYm)) {
                currentYm = newYm;
                // 如果你有默认文件名逻辑，这里也可以顺便刷新 initialFileName
                // 例如：someLabel.setText("账本：" + currentYm);
            }
        } catch (Exception ignore) {
            // 非法值直接忽略
        }
    }

    /**
     * 默认文件名：账本-YYYY-MM.cflg
     */
    private String defaultFileName() {
        return String.format("账本-%s%s", currentYm, FILE_EXT);
    }

    /**
     * 保存时确保扩展名
     */
    private static File ensureExt(File f) {
        if (f == null) return null;
        String name = f.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(FILE_EXT) ? f : new File(f.getParentFile(), f.getName() + FILE_EXT);
    }

    /**
     * 是否有记录引用了该类别
     */
    private boolean isCategoryUsed(String cat) {
        String target = trimToEmpty(cat);
        if (target.isEmpty()) return false;
        for (RecordRow r : master) if (target.equals(r.getCategory())) return true;
        return false;
    }

    /**
     * 是否有记录引用了该类别/子类
     */
    private boolean isSubUsed(String cat, String sub) {
        String c = trimToEmpty(cat), s = trimToEmpty(sub);
        if (c.isEmpty() || s.isEmpty()) return false;
        for (RecordRow r : master) if (c.equals(r.getCategory()) && s.equals(r.getSubCategory())) return true;
        return false;
    }

    /**
     * 重命名类别时，同步更新行数据里的类别名
     */
    private void updateRowsForCategoryRename(String oldName, String newName) {
        String o = trimToEmpty(oldName), n = trimToEmpty(newName);
        if (o.equals(n)) return;
        for (RecordRow r : master) {
            if (o.equals(r.getCategory())) {
                r.categoryProperty().set(n);
            }
        }
    }

    /**
     * 重命名子类时，同步更新行数据里的子类名（仅限该类别下）
     */
    private void updateRowsForSubRename(String cat, String oldSub, String newSub) {
        String c = trimToEmpty(cat), o = trimToEmpty(oldSub), n = trimToEmpty(newSub);
        if (o.equals(n)) return;
        for (RecordRow r : master) {
            if (c.equals(r.getCategory()) && o.equals(r.getSubCategory())) {
                r.subCategoryProperty().set(n);
            }
        }
    }

    /**
     * 简单文本输入对话框
     */
    private String promptText(String title, String header, String initial) {
        TextInputDialog d = new TextInputDialog(initial == null ? "" : initial);
        d.setTitle(title);
        d.setHeaderText(header);
        return d.showAndWait().map(String::trim).orElse(null);
    }

    // ===== 搜索 =====
    @FXML
    private void onSearch() {
        String q = Optional.ofNullable(searchField.getText()).orElse("").trim().toLowerCase();
        filtered.setPredicate(r -> {
            if (q.isEmpty()) return true;
            return formatDateTime(r.getTimestampMs()).toLowerCase().contains(q)
                    || formatYuan(r.getAmountCents()).toLowerCase().contains(q)
                    || r.getCategory().toLowerCase().contains(q)
                    || r.getSubCategory().toLowerCase().contains(q)
                    || r.getNote().toLowerCase().contains(q);
        });
    }

    // ===== 编辑对话框（类别/子类别必选；金额以元） =====
    private RecordRow showEditDialog(RecordRow base) {
        Dialog<RecordRow> dialog = new Dialog<>();
        dialog.setTitle(base == null ? "添加记录" : "编辑记录");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        TextField tsField = new TextField(base == null ? String.valueOf(System.currentTimeMillis()) : String.valueOf(base.getTimestampMs()));
        TextField amtField = new TextField(base == null ? "0.00" : formatYuanPlain(base.getAmountCents()));

        ComboBox<String> catBox = new ComboBox<>(categoryChoices);
        catBox.setEditable(true);
        catBox.setConverter(PlainStringConverter.INSTANCE);

        ComboBox<String> subBox = new ComboBox<>();
        subBox.setEditable(true);
        subBox.setConverter(PlainStringConverter.INSTANCE);

        if (base != null) {
            setComboText(catBox, base.getCategory());
            refreshSubChoices(catBox, subBox, base.getSubCategory());
        } else {
            setComboText(catBox, "");
            refreshSubChoices(catBox, subBox, "");
        }

        // 类别变化 -> 子类联动
        catBox.valueProperty().addListener((o, ov, nv) -> refreshSubChoices(catBox, subBox, ""));
        catBox.getEditor().textProperty().addListener((o, ov, nv) -> refreshSubChoices(catBox, subBox, ""));

        TextField noteField = new TextField(base == null ? "" : base.getNote());

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.addRow(0, new Label("时间戳(ms)"), tsField);
        grid.addRow(1, new Label("金额(元)"), amtField);
        grid.addRow(2, new Label("类别(必选)"), catBox);
        grid.addRow(3, new Label("子类别(必选)"), subBox);
        grid.addRow(4, new Label("备注"), noteField);

        dialog.getDialogPane().setContent(grid);

        // 校验
        Button okBtn = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        okBtn.addEventFilter(javafx.event.ActionEvent.ACTION, evt -> {
            try {
                Long.parseLong(tsField.getText().trim());
                yuanToCents(amtField.getText().trim());
                String cat = trimToEmpty(getComboText(catBox));
                String sub = trimToEmpty(getComboText(subBox));
                if (cat.isEmpty()) throw new IllegalArgumentException("类别不能为空");
                if (sub.isEmpty()) throw new IllegalArgumentException("子类别不能为空");
            } catch (Exception ex) {
                evt.consume();
                showError(ex.getMessage());
            }
        });

        dialog.setResultConverter(bt -> {
            if (bt != ButtonType.OK) return null;
            long ts = Long.parseLong(tsField.getText().trim());
            long cents = yuanToCents(amtField.getText().trim());
            String cat = trimToEmpty(getComboText(catBox));
            String sub = trimToEmpty(getComboText(subBox));
            String note = Optional.ofNullable(noteField.getText()).orElse("").trim();

            // ★ 将新选项并入 taxonomy，并刷新下拉（保持全量）
            ensureInTaxonomy(cat, sub);
            rebuildChoicesFromTaxonomy(taxonomy);

            return new RecordRow(ts, cents, cat, sub, note);
        });

        return dialog.showAndWait().orElse(null);
    }

    // ===== taxonomy 并入与下拉联动 =====

    /**
     * 若输入的新类别/子类别不在 taxonomy 中，这里并入（类别与其子类相互独立编号）
     */
    private void ensureInTaxonomy(String cat, String sub) {
        String c = trimToEmpty(cat);
        String s = trimToEmpty(sub);
        if (c.isEmpty() || s.isEmpty()) return;
        int catId = taxonomy.categoryIdOf(c); // 不存在则追加
        taxonomy.subIdOf(catId, s);           // 不存在则追加
    }

    /**
     * 根据 taxonomy 重建下拉候选
     */
    private void rebuildChoicesFromTaxonomy(CategoryTaxonomy tax) {
        categoryChoices.setAll(tax.categories());
        subChoicesByCat.clear();
        for (String c : tax.categories()) {
            subChoicesByCat.put(c, FXCollections.observableArrayList(tax.subsOf(c)));
        }
    }

    /**
     * 类别变化时刷新子类候选；prefer 在存在时回填
     */
    private void refreshSubChoices(ComboBox<String> catBox, ComboBox<String> subBox, String prefer) {
        String cat = trimToEmpty(getComboText(catBox));
        ObservableList<String> items = subChoicesByCat.getOrDefault(cat, FXCollections.observableArrayList());
        subBox.setItems(items);
        if (prefer != null && !prefer.isBlank() && items.contains(prefer)) {
            setComboText(subBox, prefer);
        } else {
            setComboText(subBox, "");
        }
    }

    /**
     * 仅用于兜底：从行构建一次 taxonomy（正常流程不再依赖此函数）
     */
    private CategoryTaxonomy buildTaxonomyFromRows(List<RecordRow> rows) {
        Map<String, Set<String>> map = new TreeMap<>();
        for (RecordRow r : rows) {
            String c = trimToEmpty(r.getCategory());
            String s = trimToEmpty(r.getSubCategory());
            if (c.isEmpty() || s.isEmpty()) continue;
            map.computeIfAbsent(c, k -> new TreeSet<>()).add(s);
        }
        List<String> cats = new ArrayList<>(map.keySet());
        List<List<String>> subs = cats.stream()
                .map(c -> new ArrayList<>(map.get(c)))
                .collect(Collectors.toList());
        return new CategoryTaxonomy(cats, subs);
    }

    // ===== 工具：金额/日期格式化 =====
    private static final DecimalFormat YUAN_FMT = new DecimalFormat("0.00");
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static String formatYuan(long cents) {
        BigDecimal yuan = BigDecimal.valueOf(cents).movePointLeft(2);
        return YUAN_FMT.format(yuan);
    }

    private static String formatYuanPlain(long cents) {
        return BigDecimal.valueOf(cents).movePointLeft(2).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static long yuanToCents(String yuanText) {
        String t = Objects.requireNonNull(yuanText).trim().replace(",", "");
        BigDecimal bd = new BigDecimal(t).setScale(2, RoundingMode.HALF_UP);
        return bd.movePointRight(2).longValueExact();
    }

    private static String formatDateTime(long epochMs) {
        // 本地时区显示
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault()).format(DTF);
    }

    // ===== 小工具 =====
    private Stage getStage() {
        return (Stage) tableView.getScene().getWindow();
    }

    private void showError(String msg) {
        new Alert(Alert.AlertType.ERROR, msg).showAndWait();
    }

    private void showInfo(String msg) {
        new Alert(Alert.AlertType.INFORMATION, msg).showAndWait();
    }

    private static String trimToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    private static void setComboText(ComboBox<String> box, String text) {
        if (box.isEditable()) box.getEditor().setText(text == null ? "" : text);
        box.setValue((text == null || text.isBlank()) ? null : text);
    }

    private static String getComboText(ComboBox<String> box) {
        return box.isEditable() ? box.getEditor().getText() : box.getValue();
    }

    private static final class PlainStringConverter extends StringConverter<String> {
        static final PlainStringConverter INSTANCE = new PlainStringConverter();

        @Override
        public String toString(String object) {
            return object == null ? "" : object;
        }

        @Override
        public String fromString(String string) {
            return string == null ? "" : string;
        }
    }

    // ===== 金额列编辑支持（显示元、回写分） =====
    private static final class YuanEditCell extends TextFieldTableCell<RecordRow, Long> {
        private YuanEditCell() {
            super(new LongStringConverterWithYuan());
        }

        @Override
        public void updateItem(Long cents, boolean empty) {
            super.updateItem(cents, empty);
            if (!empty && cents != null) setText(formatYuan(cents));
        }
    }

    private static final class LongStringConverterWithYuan extends StringConverter<Long> {
        @Override
        public String toString(Long cents) {
            return cents == null ? "" : formatYuan(cents);
        }

        @Override
        public Long fromString(String yuan) {
            return yuanToCents(yuan);
        }
    }

    // ===== 行模型 =====
    public static final class RecordRow {
        private final LongProperty timestampMs = new SimpleLongProperty();
        private final LongProperty amountCents = new SimpleLongProperty();
        private final StringProperty category = new SimpleStringProperty();
        private final StringProperty subCategory = new SimpleStringProperty();
        private final StringProperty note = new SimpleStringProperty();

        public RecordRow(long tsMs, long cents, String cat, String sub, String note) {
            this.timestampMs.set(tsMs);
            this.amountCents.set(cents);
            this.category.set(cat == null ? "" : cat);
            this.subCategory.set(sub == null ? "" : sub);
            this.note.set(note == null ? "" : note);
        }

        public long getTimestampMs() {
            return timestampMs.get();
        }

        public long getAmountCents() {
            return amountCents.get();
        }

        public String getCategory() {
            return category.get();
        }

        public String getSubCategory() {
            return subCategory.get();
        }

        public String getNote() {
            return note.get();
        }

        public LongProperty timestampMsProperty() {
            return timestampMs;
        }

        public LongProperty amountCentsProperty() {
            return amountCents;
        }

        public StringProperty categoryProperty() {
            return category;
        }

        public StringProperty subCategoryProperty() {
            return subCategory;
        }

        public StringProperty noteProperty() {
            return note;
        }
    }
}