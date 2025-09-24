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

import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import top.spco.cashflow.importer.config.RuleConfigs;
import top.spco.cashflow.importer.core.BillImporterService;
import top.spco.cashflow.model.RecordRow;
import top.spco.cashflow.service.LedgerFileService;
import top.spco.cashflow.service.TaxonomyService;
import top.spco.cashflow.ui.category.CategoryEditorController;
import top.spco.cashflow.ui.category.CategoryEditorResult;
import top.spco.cashflow.ui.components.YuanCell;
import top.spco.cashflow.ui.importing.ImportPreviewController;
import top.spco.cashflow.ui.record.RecordEditorController;
import top.spco.cashflow.util.Amounts;
import top.spco.cashflow.util.Dates;
import top.spco.cashflow.util.StringUtil;
import top.spco.cashflow.viewmodel.LedgerViewModel;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

import static top.spco.cashflow.util.AlertUtil.showError;
import static top.spco.cashflow.util.AlertUtil.showInfo;

public class MainController {
    private static final String FILE_EXT = ".cflg";
    private static final String FILE_DESC = "Cashflow Ledger (*" + FILE_EXT + ")";

    private final LedgerViewModel vm = new LedgerViewModel();
    private final TaxonomyService taxonomySvc = new TaxonomyService();
    private final LedgerFileService fileSvc = new LedgerFileService();

    // MARK: FXML
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
    private final ObservableList<String> categoryChoices = taxonomySvc.categories();
    private FilteredList<RecordRow> filtered;
    private SortedList<RecordRow> sorted;

    private void markDirty() {
        vm.markDirty();
        updateWindowTitle();
    }

    private void clearDirty() {
        vm.clearDirty();
        updateWindowTitle();
    }

    private void updateWindowTitle() {
        Stage s = getStage();
        if (s == null) return;
        String name = (vm.getCurrentFile() != null) ? vm.getCurrentFile().getName() : ("未命名" + FILE_EXT);
        String title = (vm.isDirty() ? "* " : "") + name + "  -  " + vm.getYearMonth();
        s.setTitle(title);
    }

    // MARK: Initialize
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
                setText(Dates.formatDateTime(epochMs));
            }
        });

        // 金额：显示元；编辑时用元输入，内部存“分”
        colAmount.setCellFactory(col -> new YuanCell<>());
        tableView.setEditable(true);

        // 过滤 + 排序
        filtered = new FilteredList<>(vm.getEntries(), r -> true);
        sorted = new SortedList<>(filtered);
        sorted.comparatorProperty().bind(tableView.comparatorProperty());
        tableView.setItems(sorted);

        vm.getEntries().addListener((ListChangeListener<RecordRow>) c -> markDirty());

        // 默认按时间戳升序
        colTimestamp.setSortType(TableColumn.SortType.ASCENDING);
        tableView.getSortOrder().add(colTimestamp);

        initYearMonthPickers();         // 初始化年/月控件
        syncYearMonthPickersFromState(); // 与 currentYm 同步显示

        enableDragOpen(tableView);
        tableView.sceneProperty().addListener((obs, oldSc, sc) -> {
            if (sc != null) enableDragOpen(sc.getRoot());
        });
    }

    // 安装窗口关闭拦截：未保存时提示
    public void installCloseGuard(Stage stage) {
        stage.setOnCloseRequest(evt -> {
            if (!vm.isDirty()) return; // 干净，直接关
            ButtonType SAVE = new ButtonType("保存", ButtonBar.ButtonData.YES);
            ButtonType NO = new ButtonType("不保存", ButtonBar.ButtonData.NO);
            ButtonType CANCEL = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);

            Alert a = new Alert(Alert.AlertType.CONFIRMATION, "文件已修改，是否在退出前保存？", SAVE, NO, CANCEL);
            a.setTitle("未保存的更改");
            Optional<ButtonType> r = a.showAndWait();

            if (r.isEmpty() || r.get() == CANCEL) {
                evt.consume(); // 取消关闭
                return;
            }
            if (r.get() == SAVE) {
                try {
                    onSave();            // 复用现有保存逻辑
                    if (vm.isDirty()) {         // 若保存失败（仍是脏），阻止关闭
                        evt.consume();
                    }
                } catch (Exception e) {
                    evt.consume();
                    showError("保存失败：" + e.getMessage());
                }
            }
            // 选择“不保存”则直接放行
        });
    }

    // ===== 文件操作（分类树结构） =====
    @FXML
    private void onAnalyzeFile() {
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().setAll(new FileChooser.ExtensionFilter("Cashflow Ledger (*.cflg)", "*.cflg"), new FileChooser.ExtensionFilter("所有文件 (*.*)", "*.*"));
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
        if (vm.getCurrentFile() == null) {
            showError("请先打开一个账本文件");
            return;
        }
        try {
            LedgerAnalyzer.showAnalysis((Stage) tableView.getScene().getWindow(), vm.getCurrentFile());
        } catch (IOException ex) {
            showError("分析失败：" + ex.getMessage());
        }
    }

    private void loadFile(File f) {
        try {
            fileSvc.open(f, vm, taxonomySvc);
            tableView.sort();
            if (yearBox != null && monthBox != null) syncYearMonthPickersFromState();
            clearDirty();
        } catch (IOException ex) {
            showError("读取失败：" + ex.getMessage());
        }
    }

    @FXML
    private void onOpen() {
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().setAll(new FileChooser.ExtensionFilter("Cashflow Ledger (*.cflg)", "*.cflg"), new FileChooser.ExtensionFilter("所有文件 (*.*)", "*.*"));
        File f = fc.showOpenDialog(getStage());
        if (f != null) loadFile(f);
    }

    @FXML
    private void onSave() {
        try {
            if (vm.getCurrentFile() == null) {
                FileChooser fc = new FileChooser();
                fc.getExtensionFilters().setAll(new FileChooser.ExtensionFilter(FILE_DESC, "*" + FILE_EXT));
                fc.setInitialFileName(defaultFileName());
                File chosen = fc.showSaveDialog(getStage());
                if (chosen == null) return;
                fileSvc.saveAs(ensureExt(chosen), vm, taxonomySvc);
            } else {
                fileSvc.save(vm, taxonomySvc);
            }
            clearDirty();
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
        var res = RecordEditorController.showEdit(getStage(), null, categoryChoices, taxonomySvc::subsOf);
        res.ifPresent(rr -> {
            taxonomySvc.ensure(rr.getCategory(), rr.getSubCategory());  // 真相只加 service
            vm.getEntries().add(rr);
            markDirty();
            tableView.sort();
        });
    }

    @FXML
    private void onEdit() {
        var sel = tableView.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        var res = RecordEditorController.showEdit(getStage(), sel, categoryChoices, taxonomySvc::subsOf);
        res.ifPresent(rr -> {
            int idx = vm.getEntries().indexOf(sel);
            vm.getEntries().set(idx, rr);
            taxonomySvc.ensure(rr.getCategory(), rr.getSubCategory());
            markDirty();
            tableView.sort();
        });
    }

    @FXML
    private void onQuickAdd() {
        int initDay = (lastQuickDay == null) ? Math.min(LocalDate.now().getDayOfMonth(), vm.getYearMonth().lengthOfMonth()) : lastQuickDay;

        var result = RecordEditorController.showQuickAdd(getStage(), vm::getYearMonth, initDay, categoryChoices, taxonomySvc::subsOf,            // 直接从 service 取子类
                rr -> {                          // onAddEach
                    taxonomySvc.ensure(rr.getCategory(), rr.getSubCategory());
                    vm.getEntries().add(rr);
                    markDirty();
                    tableView.sort();
                });
        lastQuickDay = result.lastDay();
    }

    @FXML
    private void onImportWeChatPay() {
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV 或 明细文件", "*.csv", "*.txt", "*.*"));
        File bill = fc.showOpenDialog(getStage());
        if (bill == null) return;

        FileChooser rc = new FileChooser();
        rc.getExtensionFilters().add(new FileChooser.ExtensionFilter("规则配置 (YAML)", "*.yaml", "*.yml"));
        rc.setTitle("选择导入规则（可选）");
        File rulesFile = rc.showOpenDialog(getStage());


        try {
            var cfg = (rulesFile != null) ? RuleConfigs.load(rulesFile) : RuleConfigs.empty();
            var importer = new BillImporterService();
            var finals = importer.importFile(bill, cfg);     // List<UnifiedTxn>，含已按规则映射/丢弃后的结果

            if (finals.isEmpty()) {
                showInfo("没有可导入的记录。");
                return;
            }

            if (cfg.defaults.onlyAppendFromLastDate) {
                OptionalLong maxTsOpt = vm.getEntries().stream().mapToLong(RecordRow::getTimestampMs).max();
                if (maxTsOpt.isPresent()) {
                    long maxTs = maxTsOpt.getAsLong();

                    // 把“最新记录”的时间戳取到“当天 00:00:00”的毫秒，用它作为‘>=’的门槛
                    LocalDate lastDate = Dates.toLocalDate(maxTs);
                    long cutoffStartOfDayMs = Dates.startOfDayMillis(lastDate);

                    int before = finals.size();
                    finals = finals.stream().filter(t -> t.timestampMs() >= cutoffStartOfDayMs).toList();
                    int dropped = before - finals.size();

                    // （可选）给预览弹窗传个提示，比如：“已按配置仅保留 ≥ 2025-05-02 的记录（忽略 12 条）”
                    // preview.setBanner(String.format("已按配置仅保留 ≥ %s 的记录（忽略 %d 条）", lastDate, dropped));
                }
            }

            var rowsOpt = ImportPreviewController.show(getStage(), finals, taxonomySvc);
            if (rowsOpt.isEmpty()) return; // 用户取消

            var rows = rowsOpt.get();
            for (var r : rows) {
                if (StringUtil.trimToEmpty(r.getCategory()).isEmpty() || StringUtil.trimToEmpty(r.getSubCategory()).isEmpty()) {
                    showError("存在未填写分类/子类的记录，导入中止。");
                    return;
                }
            }

            for (var r : rows) {
                taxonomySvc.ensure(r.getCategory(), r.getSubCategory());
                vm.getEntries().add(r);
            }

            tableView.sort();
            markDirty();
            showInfo("导入完成：共 " + rows.size() + " 条。");
        } catch (Exception ex) {
            showError("导入失败：" + ex.getMessage());
        }
    }

    @FXML
    private void onDelete() {
        int idx = tableView.getSelectionModel().getSelectedIndex();
        if (idx < 0) return;

        RecordRow toRemove = sorted.get(idx);

        vm.getEntries().remove(toRemove);
        markDirty();

        // 体验：尽量选中删除后位置的下一条
        int next = Math.min(idx, sorted.size() - 1);
        if (next >= 0) {
            tableView.getSelectionModel().clearAndSelect(next);
            tableView.scrollTo(Math.max(0, next - 3));
        }
    }

    @FXML
    public void onEditCategory() {
        var resOpt = CategoryEditorController.show(getStage(), taxonomySvc.getTaxonomy(), cat -> taxonomySvc.isCategoryUsed(vm.getEntries(), cat), (cat, sub) -> taxonomySvc.isSubUsed(vm.getEntries(), cat, sub));
        if (resOpt.isEmpty()) return;

        var res = resOpt.get();

        // 1) 推进唯一真相到 service
        taxonomySvc.setTaxonomy(res.taxonomy());

        // 2) 回放“重命名操作”以同步表格行文本
        for (var op : res.ops()) {
            if (op instanceof CategoryEditorResult.CategoryRename(String oldName, String newName)) {
                updateRowsForCategoryRename(oldName, newName);
            } else if (op instanceof CategoryEditorResult.SubRename(String catName, String oldSub, String newSub)) {
                updateRowsForSubRename(catName, oldSub, newSub);
            }
        }

        tableView.refresh();
        markDirty();
    }

    // MARK: Utils
    private static final String EXT_MAIN = ".cflg";
    private static final String EXT_LEGACY = ".bin";

    private boolean isSupportedExt(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(EXT_MAIN) || n.endsWith(EXT_LEGACY);
    }

    private boolean dragboardHasSupportedFile(Dragboard db) {
        if (!db.hasFiles()) return false;
        for (File f : db.getFiles()) if (isSupportedExt(f.getName())) return true;
        return false;
    }

    public void openFromExternalPath(String path) {
        if (path == null || path.isBlank()) return;
        File f = new File(path);
        if (!f.exists()) {
            showError("文件不存在: " + path);
            return;
        }
        if (!isSupportedExt(f.getName())) {
            showError("不支持的文件类型: " + path);
            return;
        }
        javafx.application.Platform.runLater(() -> loadFile(f));
    }

    private void enableDragOpen(Node target) {
        target.setOnDragOver(e -> {
            if (e.getGestureSource() != target && dragboardHasSupportedFile(e.getDragboard())) {
                e.acceptTransferModes(TransferMode.COPY);
            }
            e.consume();
        });

        target.setOnDragDropped(e -> {
            boolean success = false;
            var db = e.getDragboard();
            try {
                if (db.hasFiles()) {
                    // 只取首个受支持的文件
                    Optional<File> first = db.getFiles().stream().filter(f -> isSupportedExt(f.getName())).findFirst();
                    if (first.isPresent()) {
                        // 有未保存更改 -> 提示
                        if (vm.isDirty()) {
                            ButtonType SAVE = new ButtonType("保存", ButtonBar.ButtonData.YES);
                            ButtonType NO = new ButtonType("不保存", ButtonBar.ButtonData.NO);
                            ButtonType CANCEL = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
                            Alert a = new Alert(Alert.AlertType.CONFIRMATION, "当前文件有未保存更改，打开新文件前是否保存？", SAVE, NO, CANCEL);
                            a.setTitle("未保存的更改");
                            var r = a.showAndWait();
                            if (r.isEmpty() || r.get() == CANCEL) {
                                e.setDropCompleted(false);
                                e.consume();
                                return; // 取消打开
                            }
                            if (r.get() == SAVE) {
                                onSave();
                                if (vm.isDirty()) { // 保存失败/被取消
                                    e.setDropCompleted(false);
                                    e.consume();
                                    return;
                                }
                            }
                            // 选“不保存”则直接继续
                        }

                        loadFile(first.get()); // ← 用统一的打开入口
                        success = true;
                    } else {
                        showError("不支持的文件类型，只支持 *.cflg / *.bin");
                    }
                }
            } finally {
                e.setDropCompleted(success);
                e.consume();
            }
        });
    }

    private void initYearMonthPickers() {
        // 年份：当前年±10
        int y = vm.getYearMonth().getYear();
        ObservableList<Integer> years = FXCollections.observableArrayList();
        for (int i = y - 10; i <= y + 10; i++) years.add(i);
        yearBox.setItems(years);

        // 月份：1..12
        monthBox.setItems(FXCollections.observableArrayList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12));

        // 监听修改 -> 更新 currentYm
        yearBox.valueProperty().addListener((o, ov, nv) -> applyYearMonthFromPickers());
        monthBox.valueProperty().addListener((o, ov, nv) -> applyYearMonthFromPickers());
    }

    private void syncYearMonthPickersFromState() {
        updatingYmPickers = true;
        try {
            int y = vm.getYearMonth().getYear();
            int m = vm.getYearMonth().getMonthValue();

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
            if (!Objects.equals(newYm, vm.getYearMonth())) {
                vm.setYearMonth(newYm);
                markDirty();         // 年月变了，需保存
                updateWindowTitle(); // 同步标题
                // ★ 如果上次记住的“日”超出新月份范围，则夹断
                if (lastQuickDay != null) {
                    int max = vm.getYearMonth().lengthOfMonth();
                    if (lastQuickDay > max) lastQuickDay = max;
                }
            }
        } catch (Exception ignore) {
            // 非法值直接忽略
        }
    }

    /**
     * 默认文件名：账本-YYYY-MM.cflg
     */
    private String defaultFileName() {
        return String.format("账本-%s%s", vm.getYearMonth(), FILE_EXT);
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
     * 重命名类别时，同步更新行数据里的类别名
     */
    private void updateRowsForCategoryRename(String oldName, String newName) {
        String o = StringUtil.trimToEmpty(oldName), n = StringUtil.trimToEmpty(newName);
        if (o.equals(n)) return;
        for (RecordRow r : vm.getEntries()) {
            if (o.equals(r.getCategory())) {
                r.categoryProperty().set(n);
            }
        }
    }

    /**
     * 重命名子类时，同步更新行数据里的子类名（仅限该类别下）
     */
    private void updateRowsForSubRename(String cat, String oldSub, String newSub) {
        String c = StringUtil.trimToEmpty(cat), o = StringUtil.trimToEmpty(oldSub), n = StringUtil.trimToEmpty(newSub);
        if (o.equals(n)) return;
        for (RecordRow r : vm.getEntries()) {
            if (c.equals(r.getCategory()) && o.equals(r.getSubCategory())) {
                r.subCategoryProperty().set(n);
            }
        }
    }

    // ===== 搜索 =====
    @FXML
    private void onSearch() {
        String q = Optional.ofNullable(searchField.getText()).orElse("").trim().toLowerCase();
        filtered.setPredicate(r -> {
            if (q.isEmpty()) return true;
            return Dates.formatDateTime(r.getTimestampMs()).toLowerCase().contains(q) || Amounts.formatYuan(r.getAmountCents()).toLowerCase().contains(q) || r.getCategory().toLowerCase().contains(q) || r.getSubCategory().toLowerCase().contains(q) || r.getNote().toLowerCase().contains(q);
        });
    }

    // ===== 小工具 =====
    private Stage getStage() {
        return (Stage) tableView.getScene().getWindow();
    }
}