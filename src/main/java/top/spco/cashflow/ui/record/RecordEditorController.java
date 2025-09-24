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
package top.spco.cashflow.ui.record;

import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import top.spco.cashflow.model.RecordRow;
import top.spco.cashflow.ui.components.Converters;
import top.spco.cashflow.util.Amounts;
import top.spco.cashflow.util.Dates;
import top.spco.cashflow.util.MathUtil;
import top.spco.cashflow.util.StringUtil;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class RecordEditorController {
    @FXML
    private HBox paneQuick;
    @FXML
    private HBox paneTimestamp;

    @FXML
    private Label lblYm;
    @FXML
    private TextField tfDay;
    @FXML
    private TextField tfTimestamp;

    @FXML
    private TextField tfAmount;
    @FXML
    private ComboBox<String> cbCategory;
    @FXML
    private ComboBox<String> cbSub;
    @FXML
    private TextField tfNote;

    // ===== 状态 / 依赖
    public enum Mode {EDIT, QUICK}

    private Function<String, ObservableList<String>> subsProvider;

    // Quick 模式专用
    private Supplier<YearMonth> ymSupplier; // 每次“添加下一条”实时取当下 YM
    private int lastDay;                     // 记住“日”
    private int addedCount = 0;              // 统计添加条数
    private Consumer<RecordRow> quickAddConsumer;

    // ===== 初始化
    @FXML
    private void initialize() {
        // 让 ComboBox 以“纯字符串”行为工作
        cbCategory.setConverter(Converters.INSTANCE);
        cbSub.setConverter(Converters.INSTANCE);

        // 类别变化 -> 子类联动（值变化 + 文本变化）
        ChangeListener<Object> relay = (o, ov, nv) -> refreshSubChoices("");
        cbCategory.valueProperty().addListener(relay);
        cbCategory.getEditor().textProperty().addListener(relay);
    }

    private void switchMode(Mode m) {
        boolean quick = (m == Mode.QUICK);
        setVisibleManaged(paneQuick, quick);
        setVisibleManaged(paneTimestamp, !quick);
    }

    private static void setVisibleManaged(Node node, boolean v) {
        node.setVisible(v);
        node.setManaged(v);
    }

    // ===== 对外 API：编辑模式 =====
    public static Optional<RecordRow> showEdit(Stage owner,
                                               RecordRow base,
                                               ObservableList<String> categoryChoices,
                                               java.util.function.Function<String, ObservableList<String>> subsProvider) {
        try {
            FXMLLoader ldr = new FXMLLoader(RecordEditorController.class.getResource("/top/spco/cashflow/ui/record/record_editor.fxml"));
            Dialog<RecordRow> dlg = new Dialog<>();
            dlg.initOwner(owner);
            dlg.initModality(Modality.WINDOW_MODAL);
            dlg.setTitle(base == null ? "添加记录" : "编辑记录");
            dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

            GridPane content = ldr.load();
            RecordEditorController c = ldr.getController();
            c.setupChoices(categoryChoices, subsProvider);
            c.switchMode(Mode.EDIT);

            // 初始值
            if (base == null) {
                c.tfTimestamp.setText(String.valueOf(System.currentTimeMillis()));
                c.tfAmount.setText("0.00");
                c.setComboText(c.cbCategory, "");
                c.refreshSubChoices("");
                c.tfNote.setText("");
            } else {
                c.tfTimestamp.setText(String.valueOf(base.getTimestampMs()));
                c.tfAmount.setText(Amounts.formatYuanPlain(base.getAmountCents()));
                c.setComboText(c.cbCategory, base.getCategory());
                c.refreshSubChoices(base.getSubCategory());
                c.tfNote.setText(base.getNote());
            }

            // 校验
            Button okBtn = (Button) dlg.getDialogPane().lookupButton(ButtonType.OK);
            okBtn.addEventFilter(javafx.event.ActionEvent.ACTION, evt -> {
                try {
                    Long.parseLong(c.tfTimestamp.getText().trim());
                    Amounts.yuanToCents(c.tfAmount.getText().trim());
                    String cat = StringUtil.trimToEmpty(c.getComboText(c.cbCategory));
                    String sub = StringUtil.trimToEmpty(c.getComboText(c.cbSub));
                    if (cat.isEmpty()) throw new IllegalArgumentException("类别不能为空");
                    if (sub.isEmpty()) throw new IllegalArgumentException("子类别不能为空");
                } catch (Exception ex) {
                    evt.consume();
                    showError("校验失败：" + ex.getMessage());
                }
            });

            dlg.getDialogPane().setContent(content);
            dlg.setResultConverter(bt -> {
                if (bt != ButtonType.OK) return null;
                long ts = Long.parseLong(c.tfTimestamp.getText().trim());
                long cents = Amounts.yuanToCents(c.tfAmount.getText().trim());
                String cat = StringUtil.trimToEmpty(c.getComboText(c.cbCategory));
                String sub = StringUtil.trimToEmpty(c.getComboText(c.cbSub));
                String note = StringUtil.trimToEmpty(c.tfNote.getText());
                return new RecordRow(ts, cents, cat, sub, note);
            });

            // 初始焦点：金额
            dlg.setOnShown(e -> {
                c.tfAmount.requestFocus();
                c.tfAmount.selectAll();
            });

            return dlg.showAndWait();
        } catch (IOException e) {
            showError("无法打开编辑窗口：" + e.getMessage());
            return Optional.empty();
        }
    }

    // ===== 对外 API：快速添加模式 =====
    public static QuickAddResult showQuickAdd(Stage owner,
                                              Supplier<YearMonth> ymSupplier,
                                              int initialDay,
                                              ObservableList<String> categoryChoices,
                                              java.util.function.Function<String, ObservableList<String>> subsProvider,
                                              Consumer<RecordRow> onAddEach) {
        QuickAddResult out = new QuickAddResult(initialDay, 0);
        try {
            FXMLLoader ldr = new FXMLLoader(RecordEditorController.class.getResource("/top/spco/cashflow/ui/record/record_editor.fxml"));
            Dialog<ButtonType> dlg = new Dialog<>();
            dlg.initOwner(owner);
            dlg.initModality(Modality.WINDOW_MODAL);
            dlg.setTitle("快速添加");

            ButtonType ADD_NEXT = new ButtonType("添加下一条", ButtonBar.ButtonData.APPLY);
            dlg.getDialogPane().getButtonTypes().addAll(ADD_NEXT, ButtonType.CLOSE);

            GridPane content = ldr.load();
            RecordEditorController c = ldr.getController();
            c.setupChoices(categoryChoices, subsProvider);
            c.switchMode(Mode.QUICK);
            c.ymSupplier = Objects.requireNonNull(ymSupplier);
            c.quickAddConsumer = Objects.requireNonNull(onAddEach);

            // 初始化 YM + 日
            YearMonth ymNow = ymSupplier.get();
            c.lblYm.setText(ymNow.toString());
            int max = ymNow.lengthOfMonth();
            c.lastDay = MathUtil.clamp(initialDay <= 0 ? Math.min(LocalDate.now().getDayOfMonth(), max) : initialDay, 1, max);
            c.tfDay.setText(String.valueOf(c.lastDay));

            // 其它输入清空
            c.tfAmount.clear();
            c.setComboText(c.cbCategory, "");
            c.refreshSubChoices("");
            c.tfNote.clear();

            Button addBtn = (Button) dlg.getDialogPane().lookupButton(ADD_NEXT);
            addBtn.addEventFilter(javafx.event.ActionEvent.ACTION, evt -> {
                try {
                    // 每次“添加”都以当前 ymSupplier 值为准，并校正“日”
                    YearMonth ym = c.ymSupplier.get();
                    int maxDay = ym.lengthOfMonth();
                    int day = Integer.parseInt(c.tfDay.getText().trim());
                    Dates.validateDayInMonth(ym, day);
                    if (day > maxDay) day = maxDay; // 再兜底一次
                    c.lastDay = day;
                    c.lblYm.setText(ym.toString());

                    long cents = parseQuickAmountToCents(c.tfAmount.getText().trim());
                    String cat = StringUtil.trimToEmpty(c.getComboText(c.cbCategory));
                    String sub = StringUtil.trimToEmpty(c.getComboText(c.cbSub));
                    String note = StringUtil.trimToEmpty(c.tfNote.getText());

                    if (cat.isEmpty()) throw new IllegalArgumentException("类别不能为空");
                    if (sub.isEmpty()) throw new IllegalArgumentException("子类别不能为空");

                    long ts = Dates.epochMsOf(ym, day, LocalTime.now());

                    RecordRow rr = new RecordRow(ts, cents, cat, sub, note);
                    c.quickAddConsumer.accept(rr);   // 交给外部添加（并做 taxonomy/排序/脏标记）

                    c.addedCount++;
                    // 清空金额/备注，保留“日”，回焦
                    c.tfAmount.clear();
                    c.tfNote.clear();
                    c.tfAmount.requestFocus();
                    c.tfAmount.selectAll();

                    evt.consume(); // 不关闭窗口
                } catch (Exception ex) {
                    evt.consume();
                    showError(ex.getMessage());
                }
            });

            dlg.getDialogPane().setContent(content);

            // Enter = 添加下一条
            c.tfAmount.setOnAction(e -> addBtn.fire());

            // 初始焦点：金额
            dlg.setOnShown(e -> {
                c.tfAmount.requestFocus();
                c.tfAmount.selectAll();
            });

            dlg.showAndWait();
            out = new QuickAddResult(c.lastDay, c.addedCount);
        } catch (IOException e) {
            showError("无法打开快速添加窗口：" + e.getMessage());
        }
        return out;
    }

    // ===== 公共工具 / 行为 =====
    private void setupChoices(ObservableList<String> categoryChoices,
                              Function<String, ObservableList<String>> subsProvider) {
        this.subsProvider = subsProvider;
        cbCategory.setItems(categoryChoices);
    }

    private void refreshSubChoices(String prefer) {
        String cat = StringUtil.trimToEmpty(getComboText(cbCategory));
        ObservableList<String> items = subsProvider.apply(cat); // 动态向 service 取
        if (items == null) items = FXCollections.observableArrayList();
        cbSub.setItems(items);
        if (prefer != null && !prefer.isBlank() && items.contains(prefer)) {
            setComboText(cbSub, prefer);
        } else {
            setComboText(cbSub, "");
        }
    }

    /**
     * 未以'+'或'-'开头 -> 视为支出（负数）
     */
    private static long parseQuickAmountToCents(String yuanText) {
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
        long abs = Amounts.yuanToCents(t);
        return sign * abs;
    }

    private static void showError(String msg) {
        new Alert(Alert.AlertType.ERROR, msg).showAndWait();
    }

    private void setComboText(ComboBox<String> box, String text) {
        if (box.isEditable()) box.getEditor().setText(text == null ? "" : text);
        box.setValue((text == null || text.isBlank()) ? null : text);
    }

    private String getComboText(ComboBox<String> box) {
        return box.isEditable() ? box.getEditor().getText() : box.getValue();
    }

    // ===== 返回结果：快速添加
    public record QuickAddResult(int lastDay, int addedCount) {
    }
}
