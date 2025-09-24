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
package top.spco.cashflow.ui.importing;

import javafx.beans.binding.Bindings;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.BorderPane;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.StringConverter;
import top.spco.cashflow.importer.core.FinalTxn;
import top.spco.cashflow.model.RecordRow;
import top.spco.cashflow.service.TaxonomyService;
import top.spco.cashflow.ui.components.YuanCell;
import top.spco.cashflow.util.Dates;
import top.spco.cashflow.util.StringUtil;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static top.spco.cashflow.util.AlertUtil.showError;

public final class ImportPreviewController {

    // ===== UI =====
    @FXML
    private BorderPane root;
    @FXML
    private TableView<Row> table;
    @FXML
    private TableColumn<Row, Boolean> colUse;
    @FXML
    private TableColumn<Row, Long> colTs;
    @FXML
    private TableColumn<Row, Long> colAmt;
    @FXML
    private TableColumn<Row, String> colCat;
    @FXML
    private TableColumn<Row, String> colSub;
    @FXML
    private TableColumn<Row, String> colNote;

    @FXML
    private Label lblSummary;
    @FXML
    private Button btnSetCat;
    @FXML
    private Button btnSetSub;
    @FXML
    public Button btnCleanNote;
    @FXML
    private Button btnToggleAll;

    // ===== 状态 =====
    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private TaxonomyService taxonomySvc;

    // ===== API =====
    public static Optional<List<RecordRow>> show(Stage owner,
                                                 List<FinalTxn> finalTxn,
                                                 TaxonomyService taxonomySvc) {
        try {
            FXMLLoader ldr = new FXMLLoader(ImportPreviewController.class.getResource("import_preview.fxml"));
            Dialog<ButtonType> dlg = new Dialog<>();
            dlg.initOwner(owner);
            dlg.initModality(Modality.WINDOW_MODAL);
            dlg.setTitle("导入预览");
            dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

            BorderPane content = ldr.load();
            ImportPreviewController c = ldr.getController();
            c.setup(finalTxn, taxonomySvc);

            // 校验：OK 之前必须所有“勾选”的行都填了分类与子类
            Button okBtn = (Button) dlg.getDialogPane().lookupButton(ButtonType.OK);
            okBtn.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
                List<Row> using = c.rows.stream().filter(r -> r.use.get()).toList();
                for (Row r : using) {
                    if (StringUtil.trimToEmpty(r.category.get()).isEmpty() ||
                            StringUtil.trimToEmpty(r.subCategory.get()).isEmpty()) {
                        e.consume();
                        showError("存在未填写分类/子类的记录，请补全或取消勾选。");
                        return;
                    }
                }
            });

            dlg.getDialogPane().setContent(content);

            Optional<ButtonType> r = dlg.showAndWait();
            if (r.isPresent() && r.get() == ButtonType.OK) {
                // 只返回已勾选的，且转为 RecordRow
                List<RecordRow> out = c.rows.stream()
                        .filter(Row::isUse)
                        .map(Row::toRecordRow)
                        .collect(Collectors.toList());
                return Optional.of(out);
            }
            return Optional.empty();
        } catch (IOException ex) {
            showError("打开导入预览失败：" + ex.getMessage());
            return Optional.empty();
        }
    }

    // ===== 初始化 =====
    @FXML
    private void initialize() {
        table.setEditable(true);

        // 勾选列
        colUse.setCellValueFactory(cd -> cd.getValue().useProperty());
        colUse.setCellFactory(CheckBoxTableCell.forTableColumn(colUse));

        // 时间戳列（显示为人类时间，编辑支持“毫秒或yyyy-MM-dd HH:mm:ss”）
        colTs.setCellValueFactory(cd -> cd.getValue().timestampMsProperty().asObject());
        colTs.setCellFactory(col -> new TextFieldTableCell<>(new TsConverter()));
        colTs.setOnEditCommit(e -> e.getRowValue().setTimestampMs(e.getNewValue()));

        // 金额列（沿用你的元编辑单元格）
        colAmt.setCellValueFactory(cd -> cd.getValue().amountCentsProperty().asObject());
        colAmt.setCellFactory(col -> new YuanCell()); // 已支持编辑
        colAmt.setOnEditCommit(e -> e.getRowValue().setAmountCents(e.getNewValue()));

        // 分类列（可自由输入，也可下拉选择现有分类）
        colCat.setCellValueFactory(cd -> cd.getValue().categoryProperty());
        colCat.setCellFactory(col -> editableComboCell(() -> taxonomySvc.categories()));
        colCat.setOnEditCommit(e -> {
            Row row = e.getRowValue();
            row.setCategory(StringUtil.trimToEmpty(e.getNewValue()));
            // 分类改了，尝试清空子类（让用户重新选），也可以保留逻辑看你喜好
            row.setSubCategory("");
            table.refresh();
        });

        // 子类列（下拉项需依赖当前行的“分类”）
        colSub.setCellValueFactory(cd -> cd.getValue().subCategoryProperty());
        colSub.setCellFactory(col -> editableComboCell(() -> {
            String cat = Optional.ofNullable(table.getSelectionModel().getSelectedItem())
                    .map(Row::getCategory).orElse("");
            var items = taxonomySvc.subsOf(cat);
            return items != null ? items : FXCollections.observableArrayList();
        }));
        colSub.setOnEditCommit(e -> e.getRowValue().setSubCategory(StringUtil.trimToEmpty(e.getNewValue())));

        // 备注列
        colNote.setCellValueFactory(cd -> cd.getValue().noteProperty());
        colNote.setCellFactory(TextFieldTableCell.forTableColumn());
        colNote.setOnEditCommit(e -> e.getRowValue().setNote(StringUtil.trimToEmpty(e.getNewValue())));

        // 页脚统计
        lblSummary.textProperty().bind(Bindings.createStringBinding(() -> {
            long total = rows.size();
            long using = rows.stream().filter(Row::isUse).count();
            long invalid = rows.stream()
                    .filter(Row::isUse)
                    .filter(r -> StringUtil.trimToEmpty(r.getCategory()).isEmpty()
                            || StringUtil.trimToEmpty(r.getSubCategory()).isEmpty())
                    .count();
            return "共 " + total + " 条；已勾选 " + using + " 条；待补全(分类/子类) " + invalid + " 条";
        }, rows));

        // 批量按钮状态
        btnSetCat.disableProperty().bind(Bindings.isEmpty(table.getSelectionModel().getSelectedItems()));
        btnSetSub.disableProperty().bind(btnSetCat.disableProperty());
        btnCleanNote.disableProperty().bind(btnSetCat.disableProperty());
    }

    private void setup(List<FinalTxn> finalTxn, TaxonomyService taxonomySvc) {
        this.taxonomySvc = Objects.requireNonNull(taxonomySvc);
        rows.setAll(finalTxn.stream().map(Row::fromFinal).toList());
        table.setItems(rows);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
    }

    // ===== 事件 =====
    @FXML
    private void onToggleAll() {
        boolean anyUnchecked = rows.stream().anyMatch(r -> !r.isUse());
        for (var r : rows) r.setUse(anyUnchecked); // 全选或全不选
        table.refresh();
    }

    @FXML
    private void onBulkSetCategory() {
        var text = prompt("批量设置【分类】", "对所选行设置分类：", "");
        if (text == null) return;
        for (var r : table.getSelectionModel().getSelectedItems()) {
            r.setCategory(StringUtil.trimToEmpty(text));
            r.setSubCategory(""); // 分类改了，清空子类
        }
        table.refresh();
    }

    @FXML
    private void onBulkSetSub() {
        var cat = Optional.ofNullable(table.getSelectionModel().getSelectedItem()).map(Row::getCategory).orElse("");
        var text = prompt("批量设置【子类】", "对所选行设置子类：", "");
        if (text == null) return;
        for (var r : table.getSelectionModel().getSelectedItems()) {
            // 可不强制同一分类，这里允许自由设置
            r.setSubCategory(StringUtil.trimToEmpty(text));
        }
        table.refresh();
    }

    public void onBulkCleanNote() {
        for (var r : table.getSelectionModel().getSelectedItems()) {
            r.setNote("");
        }
        table.refresh();
    }

    // ===== 小工具 =====
    private static String prompt(String title, String header, String init) {
        TextInputDialog d = new TextInputDialog(init == null ? "" : init);
        d.setTitle(title);
        d.setHeaderText(header);
        return d.showAndWait().map(String::trim).orElse(null);
    }

    private static TextFieldTableCell<Row, String> editableComboCell(Supplier<ObservableList<String>> itemsSupplier) {
        return new TextFieldTableCell<Row, String>(new StringConverter<>() {
            @Override
            public String toString(String s) {
                return s == null ? "" : s;
            }

            @Override
            public String fromString(String s) {
                return s == null ? "" : s.trim();
            }
        }) {
            private final ComboBox<String> combo = new ComboBox<>();

            @Override
            public void startEdit() {
                super.startEdit();
                combo.setEditable(true);
                combo.setItems(itemsSupplier.get());
                combo.setValue(getItem());
                setGraphic(combo);
                setText(null);
                combo.setOnAction(e -> commitEdit(combo.getEditor().getText()));
                combo.getEditor().setOnAction(e -> commitEdit(combo.getEditor().getText()));
            }

            @Override
            public void cancelEdit() {
                super.cancelEdit();
                setGraphic(null);
                setText(getItem());
            }

            @Override
            public void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setText(null);
                    setGraphic(null);
                } else if (!isEditing()) {
                    setText(item);
                    setGraphic(null);
                }
            }
        };
    }

    // 时间戳编辑用：显示 yyyy-MM-dd HH:mm:ss；可输入毫秒或该格式
    private static final class TsConverter extends StringConverter<Long> {
        @Override
        public String toString(Long t) {
            if (t == null) return "";
            return Dates.formatDateTime(t);
        }

        @Override
        public Long fromString(String s) {
            s = StringUtil.trimToEmpty(s);
            if (s.isEmpty()) throw new IllegalArgumentException("时间不能为空");
            try {
                // 允许直接输入毫秒
                if (s.matches("\\d{10,13}")) {
                    return (s.length() == 10) ? Long.parseLong(s) * 1000L : Long.parseLong(s);
                }
                return LocalDateTime.parse(s, Dates.DTF)
                        .atZone(ZoneId.systemDefault())
                        .toInstant().toEpochMilli();
            } catch (Exception e) {
                // 回退尝试你的 Dates.parse（若有），这里简单抛错
                throw new IllegalArgumentException("时间格式不正确，示例：2025-01-31 12:34:56");
            }
        }
    }

    // ===== 预览行模型（和 RecordRow 接近，但多一个“是否导入”的开关） =====
    public static final class Row {
        private final BooleanProperty use = new SimpleBooleanProperty(true);
        private final LongProperty timestampMs = new SimpleLongProperty();
        private final LongProperty amountCents = new SimpleLongProperty();
        private final StringProperty category = new SimpleStringProperty();
        private final StringProperty subCategory = new SimpleStringProperty();
        private final StringProperty note = new SimpleStringProperty();

        public Row(long ts, long cents, String cat, String sub, String note) {
            setTimestampMs(ts);
            setAmountCents(cents);
            setCategory(cat);
            setSubCategory(sub);
            setNote(note);
        }

        static Row fromFinal(FinalTxn t) {
            return new Row(t.timestampMs(), t.amountCents(), nz(t.category()), nz(t.subCategory()), nz(t.note()));
        }

        RecordRow toRecordRow() {
            return new RecordRow(getTimestampMs(), getAmountCents(), nz(getCategory()), nz(getSubCategory()), nz(getNote()));
        }

        // props & getters/setters
        public boolean isUse() {
            return use.get();
        }

        public void setUse(boolean v) {
            use.set(v);
        }

        public BooleanProperty useProperty() {
            return use;
        }

        public long getTimestampMs() {
            return timestampMs.get();
        }

        public void setTimestampMs(long v) {
            timestampMs.set(v);
        }

        public LongProperty timestampMsProperty() {
            return timestampMs;
        }

        public long getAmountCents() {
            return amountCents.get();
        }

        public void setAmountCents(long v) {
            amountCents.set(v);
        }

        public LongProperty amountCentsProperty() {
            return amountCents;
        }

        public String getCategory() {
            return category.get();
        }

        public void setCategory(String v) {
            category.set(nz(v));
        }

        public StringProperty categoryProperty() {
            return category;
        }

        public String getSubCategory() {
            return subCategory.get();
        }

        public void setSubCategory(String v) {
            subCategory.set(nz(v));
        }

        public StringProperty subCategoryProperty() {
            return subCategory;
        }

        public String getNote() {
            return note.get();
        }

        public void setNote(String v) {
            note.set(nz(v));
        }

        public StringProperty noteProperty() {
            return note;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }
}