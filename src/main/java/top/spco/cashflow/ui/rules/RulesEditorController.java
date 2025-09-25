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
package top.spco.cashflow.ui.rules;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import top.spco.cashflow.importer.config.RuleConfig;
import top.spco.cashflow.importer.config.RuleConfigs;
import top.spco.cashflow.importer.config.RuleDef;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static top.spco.cashflow.util.AlertUtil.showError;

public final class RulesEditorController {
    @FXML
    private Node root;
    @FXML
    private TextField tfDefCategory;
    @FXML
    private TextField tfDefSub;
    @FXML
    private TextField tfDefNoteOrder;
    @FXML
    private CheckBox cbOnlyAppendFromLast;

    @FXML
    private TableView<RuleDef> tv;
    @FXML
    private TableColumn<RuleDef, String> colName, colWhen, colThen;

    private final ObservableList<RuleDef> rows = FXCollections.observableArrayList();
    private RuleConfig workingCfg = RuleConfigs.empty();
    private File workingFile = new File("rules.yaml");
    private final ObjectProperty<File> rulesFile = new SimpleObjectProperty<>();

    public static void show(Stage owner) {
        try {
            FXMLLoader ldr = new FXMLLoader(RulesEditorController.class.getResource("rules_editor.fxml"));
            Scene sc = new Scene(ldr.load());
            Stage root = new Stage();
            root.initOwner(owner);
            root.initModality(Modality.WINDOW_MODAL);
            root.setTitle("规则编辑器");
            root.setScene(sc);

            RulesEditorController c = ldr.getController();
            if (c.workingFile.exists()) c.loadFrom(c.workingFile);
            root.show();
        } catch (IOException e) {
            showError("无法打开规则编辑器：" + e.getMessage());
        }
    }

    @FXML
    private void initialize() {
        colName.setCellValueFactory(cd -> new SimpleStringProperty(nz(cd.getValue().name)));
        colWhen.setCellValueFactory(cd -> new SimpleStringProperty(WhenSummary.summary(cd.getValue())));
        colThen.setCellValueFactory(cd -> new SimpleStringProperty(ThenSummary.summary(cd.getValue())));
        tv.setItems(rows);
    }

    private void loadFrom(File f) {
        try {
            workingCfg = RuleConfigs.load(f);
            rows.setAll(workingCfg.rules == null ? List.of() : workingCfg.rules);

            if (workingCfg.defaults == null) workingCfg.defaults = new RuleConfig.Defaults();
            tfDefCategory.setText(nz(workingCfg.defaults.category));
            tfDefSub.setText(nz(workingCfg.defaults.sub));
            tfDefNoteOrder.setText(String.join(",",
                    workingCfg.defaults.noteFallbackOrder == null ? List.of() : workingCfg.defaults.noteFallbackOrder));
            cbOnlyAppendFromLast.setSelected(workingCfg.defaults.onlyAppendFromLastDate);

            workingFile = f;
        } catch (Exception e) {
            showError("读取失败：" + e.getMessage());
        }
    }

    @FXML
    private void onOpen() {
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("YAML", "*.yaml", "*.yml"));
        File f = fc.showOpenDialog(getStage());
        if (f != null) loadFrom(f);
    }

    @FXML
    private void onSave() {
        try {
            File f = rulesFile.get();
            if (f == null) {
                onSaveAs();
                return;
            }

            RuleConfig toSave = collectFromUI();
            RuleConfigs.save(f, toSave);

            // 同步内存状态
            this.workingCfg = toSave;
        } catch (IOException ex) {
            showError("保存失败：" + ex.getMessage());
        }
    }

    @FXML
    private void onSaveAs() {
        try {
            FileChooser fc = new FileChooser();
            fc.getExtensionFilters().setAll(
                    new FileChooser.ExtensionFilter("YAML 文件", "*.yaml", "*.yml"));
            File chosen = fc.showSaveDialog(root.getScene().getWindow());
            if (chosen == null) return;

            RuleConfig toSave = collectFromUI();
            RuleConfigs.save(chosen, toSave);

            // 切换“当前文件”指针 —— 避免另存为后又写回旧文件
            rulesFile.set(chosen);
            this.workingCfg = toSave;
        } catch (IOException ex) {
            showError("另存为失败：" + ex.getMessage());
        }
    }

    /**
     * 把当前 UI 内容收集成一个 RuleConfig（不直接改 workingCfg，返回一个新的）
     */
    private RuleConfig collectFromUI() {
        RuleConfig cfg = new RuleConfig();

        // defaults
        RuleConfig.Defaults d = new RuleConfig.Defaults();
        d.category = nz(tfDefCategory.getText());
        d.sub = nz(tfDefSub.getText());
        d.noteFallbackOrder = parseCsv(tfDefNoteOrder.getText());
        d.onlyAppendFromLastDate = cbOnlyAppendFromLast.isSelected();
        cfg.defaults = d;

        // rules：按你原来逻辑直接拷贝列表（如需“保存时去空”可在 RuleConfigs.save 已经处理）
        cfg.rules = new ArrayList<>(rows);

        // version 保持
        cfg.version = (workingCfg != null ? workingCfg.version : 1);
        return cfg;
    }

    @FXML
    private void onAdd() {
        RuleDef base = RuleDefFactory.defaultRule();
        RuleEditDialogController.show(getStage(), base).ifPresent(rows::add);
    }

    @FXML
    private void onEdit() {
        var sel = tv.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        RuleDef copy = RuleDefFactory.copyOf(sel);
        RuleEditDialogController.show(getStage(), copy).ifPresent(newVal -> {
            int idx = rows.indexOf(sel);
            rows.set(idx, newVal);
        });
    }

    @FXML
    private void onDelete() {
        var sel = tv.getSelectionModel().getSelectedItem();
        if (sel != null) rows.remove(sel);
    }

    @FXML
    private void onMoveUp() {
        int i = tv.getSelectionModel().getSelectedIndex();
        if (i > 0) {
            var r = rows.remove(i);
            rows.add(i - 1, r);
            tv.getSelectionModel().select(i - 1);
        }
    }

    @FXML
    private void onMoveDown() {
        int i = tv.getSelectionModel().getSelectedIndex();
        if (i >= 0 && i < rows.size() - 1) {
            var r = rows.remove(i);
            rows.add(i + 1, r);
            tv.getSelectionModel().select(i + 1);
        }
    }

    private Stage getStage() {
        return (Stage) tv.getScene().getWindow();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static List<String> parseCsv(String s) {
        if (s == null || s.isBlank()) return List.of();
        return Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).collect(Collectors.toList());
    }
}
