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

import javafx.fxml.*;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.stage.Modality;
import javafx.stage.Stage;
import top.spco.cashflow.importer.config.RuleDef;

import java.io.IOException;
import java.util.Optional;

import static top.spco.cashflow.util.AlertUtil.showError;

public final class RuleEditDialogController {

    @FXML private TextField tfName;

    // when
    @FXML private ChoiceBox<String> cbPayeeType, cbItemType, cbNoteType;
    @FXML private CheckBox cbPayeeIC, cbItemIC, cbNoteIC;
    @FXML private TextField tfPayeeVal, tfItemVal, tfNoteVal, tfAmountExpr;

    // then
    @FXML private CheckBox cbDrop;
    @FXML private TextField tfCat, tfSub, tfNoteRegex, tfNoteWith;

    private RuleDef working;

    public static Optional<RuleDef> show(Stage owner, RuleDef base) {
        try {
            FXMLLoader ldr = new FXMLLoader(RuleEditDialogController.class.getResource("rule_edit_dialog.fxml"));
            Dialog<ButtonType> dlg = new Dialog<>();
            dlg.initOwner(owner);
            dlg.initModality(Modality.WINDOW_MODAL);
            dlg.setTitle("编辑规则");
            dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
            GridPane content = ldr.load();
            RuleEditDialogController c = ldr.getController();
            c.bind(base);
            dlg.getDialogPane().setContent(content);

            Button ok = (Button) dlg.getDialogPane().lookupButton(ButtonType.OK);
            ok.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
                if (c.tfName.getText().trim().isEmpty()) {
                    e.consume();
                    showError("名称不能为空");
                }
            });

            var r = dlg.showAndWait();
            if (r.isPresent() && r.get() == ButtonType.OK) {
                return Optional.of(c.unbind());
            }
            return Optional.empty();
        } catch (IOException e) {
            e.printStackTrace();
            showError("无法打开对话框：" + e.getMessage());
            return Optional.empty();
        }
    }

    @FXML private void initialize() {
        // TextMatch 三选一：contains / equals / regex / (空=不启用)
        cbPayeeType.getItems().setAll("", "contains", "equals", "regex");
        cbItemType.getItems().setAll("", "contains", "equals", "regex");
        cbNoteType.getItems().setAll("", "contains", "equals", "regex");
    }

    private void bind(RuleDef src) {
        this.working = PojoDeepCopy.copyRule(src);

        tfName.setText(nz(working.name));

        // when.payee
        if (working.when != null && working.when.payee != null) {
            setTextMatch(working.when.payee, cbPayeeType, cbPayeeIC, tfPayeeVal);
        } else {
            clearTM(cbPayeeType, cbPayeeIC, tfPayeeVal);
        }
        // when.item
        if (working.when != null && working.when.item != null) {
            setTextMatch(working.when.item, cbItemType, cbItemIC, tfItemVal);
        } else {
            clearTM(cbItemType, cbItemIC, tfItemVal);
        }
        // when.note
        if (working.when != null && working.when.note != null) {
            setTextMatch(working.when.note, cbNoteType, cbNoteIC, tfNoteVal);
        } else {
            clearTM(cbNoteType, cbNoteIC, tfNoteVal);
        }
        // when.amount
        tfAmountExpr.setText(working.when == null ? "" : nz(working.when.amount));

        // then
        cbDrop.setSelected(Boolean.TRUE.equals(working.then.drop));
        tfCat.setText(nz(working.then.category));
        tfSub.setText(nz(working.then.sub));
        if (working.then.noteReplace != null) {
            tfNoteRegex.setText(nz(working.then.noteReplace.regex));
            tfNoteWith.setText(nz(working.then.noteReplace.with));
        } else {
            tfNoteRegex.clear();
            tfNoteWith.clear();
        }
    }

    private RuleDef unbind() {
        RuleDef out = PojoDeepCopy.copyRule(working);

        out.name = tfName.getText().trim();

        // payee
        out.when.payee = buildTM(cbPayeeType, cbPayeeIC, tfPayeeVal);
        // item
        out.when.item = buildTM(cbItemType, cbItemIC, tfItemVal);
        // note
        out.when.note = buildTM(cbNoteType, cbNoteIC, tfNoteVal);
        // amount
        out.when.amount = tfAmountExpr.getText().trim().isEmpty() ? null : tfAmountExpr.getText().trim();

        // then
        out.then.drop = cbDrop.isSelected() ? Boolean.TRUE : null;
        out.then.category = emptyToNull(tfCat.getText());
        out.then.sub = emptyToNull(tfSub.getText());
        String nr = tfNoteRegex.getText().trim();
        String nw = tfNoteWith.getText().trim();
        if (!nr.isEmpty() || !nw.isEmpty()) {
            if (out.then.noteReplace == null) out.then.noteReplace = new RuleDef.NoteReplace();
            out.then.noteReplace.regex = nr.isEmpty() ? null : nr;
            out.then.noteReplace.with = nw.isEmpty() ? null : nw;
        } else {
            out.then.noteReplace = null;
        }
        return out;
    }

    private static void setTextMatch(RuleDef.TextMatch tm, ChoiceBox<String> cb, CheckBox ic, TextField val) {
        if (tm.regex != null) { cb.setValue("regex"); val.setText(tm.regex); }
        else if (tm.equals != null) { cb.setValue("equals"); val.setText(tm.equals); }
        else if (tm.contains != null) { cb.setValue("contains"); val.setText(tm.contains); }
        else { cb.setValue(""); val.clear(); }
        ic.setSelected(tm.ignoreCase == null || tm.ignoreCase.booleanValue());
    }

    private static void clearTM(ChoiceBox<String> cb, CheckBox ic, TextField val) {
        cb.setValue(""); ic.setSelected(true); val.clear();
    }

    private static RuleDef.TextMatch buildTM(ChoiceBox<String> cb, CheckBox ic, TextField val) {
        String typ = cb.getValue();
        String v = val.getText().trim();
        if (typ == null || typ.isEmpty() || v.isEmpty()) return null;
        RuleDef.TextMatch tm = new RuleDef.TextMatch();
        switch (typ) {
            case "contains" -> tm.contains = v;
            case "equals" -> tm.equals = v;
            case "regex" -> tm.regex = v;
        }
        tm.ignoreCase = ic.isSelected(); // 为空默认为 true，这里直接存 true/false
        return tm;
    }

    private static String nz(String s){ return s==null? "":s; }
    private static String emptyToNull(String s){ s = s==null? "" : s.trim(); return s.isEmpty()? null : s; }
}
