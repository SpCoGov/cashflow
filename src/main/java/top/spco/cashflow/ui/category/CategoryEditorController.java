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
package top.spco.cashflow.ui.category;

import javafx.collections.ObservableList;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.stage.Modality;
import javafx.stage.Stage;
import top.spco.cashflow.data.CategoryTaxonomy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

import static top.spco.cashflow.ui.category.CategoryEditorResult.*;
import static top.spco.cashflow.util.AlertUtil.showError;

public final class CategoryEditorController {
    // ===== UI =====
    @FXML
    private ListView<String> lvCat;
    @FXML
    private ListView<String> lvSub;
    @FXML
    private Button btnAddCat, btnRenCat, btnDelCat;
    @FXML
    private Button btnAddSub, btnRenSub, btnDelSub;

    // ===== 数据模型（可编辑副本）=====
    private ObservableList<String> catList = FXCollections.observableArrayList();
    private final List<ObservableList<String>> subLists = new ArrayList<>();

    // 约束：是否被引用
    private Predicate<String> isCatUsed;
    private BiPredicate<String, String> isSubUsed;

    // 记录“重命名操作”的顺序，用于回放到表格行
    private final List<Op> ops = new ArrayList<>();

    // ====== 生命周期 ======
    @FXML
    private void initialize() {
        lvCat.getSelectionModel().selectedIndexProperty().addListener((o, ov, nv) -> {
            int i = nv == null ? -1 : nv.intValue();
            if (i >= 0 && i < subLists.size()) {
                lvSub.setItems(subLists.get(i));
            } else {
                lvSub.setItems(FXCollections.observableArrayList());
            }
        });
    }

    // ====== 公开 API ======
    public static Optional<CategoryEditorResult> show(Stage owner,
                                                      CategoryTaxonomy base,
                                                      Predicate<String> isCatUsed,
                                                      BiPredicate<String, String> isSubUsed) {
        try {
            FXMLLoader ldr = new FXMLLoader(CategoryEditorController.class.getResource("category_editor.fxml"));
            Dialog<ButtonType> dlg = new Dialog<>();
            dlg.initOwner(owner);
            dlg.initModality(Modality.WINDOW_MODAL);
            dlg.setTitle("编辑分类");
            dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

            GridPane content = ldr.load(); // root in fxml
            var c = ldr.getController();
            ((CategoryEditorController) c).setup(base, isCatUsed, isSubUsed);

            dlg.getDialogPane().setContent(content);

            // 校验：OK 前必须保证每个类别至少一个子类、至少一个类别
            Button okBtn = (Button) dlg.getDialogPane().lookupButton(ButtonType.OK);
            okBtn.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
                if (((CategoryEditorController) c).catList.isEmpty()) {
                    e.consume();
                    showError("至少需要一个类别");
                    return;
                }
                for (int i = 0; i < ((CategoryEditorController) c).catList.size(); i++) {
                    if (((CategoryEditorController) c).subLists.get(i).isEmpty()) {
                        e.consume();
                        showError("类别【" + ((CategoryEditorController) c).catList.get(i) + "】缺少子类");
                        return;
                    }
                }
            });

            Optional<ButtonType> r = dlg.showAndWait();
            if (r.isPresent() && r.get() == ButtonType.OK) {
                CategoryEditorController cc = (CategoryEditorController) c;
                List<String> catsFinal = new ArrayList<>(cc.catList);
                List<List<String>> subsFinal = new ArrayList<>();
                for (ObservableList<String> ol : cc.subLists) subsFinal.add(new ArrayList<>(ol));
                CategoryTaxonomy newTax = new CategoryTaxonomy(catsFinal, subsFinal);
                return Optional.of(new top.spco.cashflow.ui.category.CategoryEditorResult(newTax, cc.ops));
            }
            return Optional.empty();
        } catch (IOException ex) {
            showError("加载分类编辑器失败：" + ex.getMessage());
            return Optional.empty();
        }
    }

    private void setup(CategoryTaxonomy base,
                       Predicate<String> isCatUsed,
                       BiPredicate<String, String> isSubUsed) {
        // 可编辑副本
        this.catList = FXCollections.observableArrayList(base.categories());
        this.subLists.clear();
        for (String c : base.categories()) {
            this.subLists.add(FXCollections.observableArrayList(base.subsOf(c)));
        }
        this.lvCat.setItems(this.catList);
        if (!catList.isEmpty()) lvCat.getSelectionModel().select(0);

        this.isCatUsed = isCatUsed != null ? isCatUsed : s -> false;
        this.isSubUsed = isSubUsed != null ? isSubUsed : (c, s) -> false;
    }

    // ====== 事件处理 ======
    @FXML
    private void onAddCat() {
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
    }

    @FXML
    private void onRenameCat() {
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
            catList.set(i, newName);
            ops.add(new CategoryRename(oldName, newName)); // 记录
        }
    }

    @FXML
    private void onDeleteCat() {
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
        if (isCatUsed.test(cat)) {
            showError("类别【" + cat + "】被记录引用，不能删除");
            return;
        }

        catList.remove(i);
        subLists.remove(i);
        if (!catList.isEmpty()) lvCat.getSelectionModel().select(Math.min(i, catList.size() - 1));
    }

    @FXML
    private void onAddSub() {
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
    }

    @FXML
    private void onRenameSub() {
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
            ops.add(new SubRename(cat, oldSub, newSub)); // 记录
        }
    }

    @FXML
    private void onDeleteSub() {
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
        if (isSubUsed.test(cat, sub)) {
            showError("【" + cat + " / " + sub + "】被记录引用，不能删除");
            return;
        }

        subs.remove(j);
        if (!subs.isEmpty()) lvSub.getSelectionModel().select(Math.min(j, subs.size() - 1));
    }

    // ===== 小工具 =====
    private static String promptText(String title, String header, String initial) {
        TextInputDialog d = new TextInputDialog(initial == null ? "" : initial);
        d.setTitle(title);
        d.setHeaderText(header);
        return d.showAndWait().map(String::trim).orElse(null);
    }
}