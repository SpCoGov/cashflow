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

import top.spco.cashflow.importer.config.RuleDef;

public final class WhenSummary {
    public static String summary(RuleDef r) {
        if (r == null || r.when == null) return "";
        var w = r.when;
        StringBuilder sb = new StringBuilder();
        if (w.payee != null) sb.append("payee=").append(tm(w.payee)).append("; ");
        if (w.item  != null) sb.append("item=").append(tm(w.item)).append("; ");
        if (w.note  != null) sb.append("note=").append(tm(w.note)).append("; ");
        if (w.amount!= null) sb.append("amount=").append(w.amount).append("; ");
        return sb.toString();
    }
    private static String tm(RuleDef.TextMatch t){
        if (t.regex != null) return "regex:/" + t.regex + "/" + ic(t.ignoreCase);
        if (t.equals != null) return "equals:\"" + t.equals + "\"" + ic(t.ignoreCase);
        if (t.contains != null) return "contains:\"" + t.contains + "\"" + ic(t.ignoreCase);
        return "";
    }
    private static String ic(Boolean b){ return (b==null || b) ? "i" : ""; }
}
