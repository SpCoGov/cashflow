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

public final class ThenSummary {
    public static String summary(RuleDef r) {
        if (r == null || r.then == null) return "";
        var t = r.then;
        StringBuilder sb = new StringBuilder();
        if (Boolean.TRUE.equals(t.drop)) sb.append("drop ");
        if (t.category != null) sb.append("cat=").append(t.category).append(" ");
        if (t.sub != null) sb.append("sub=").append(t.sub).append(" ");
        if (t.noteReplace != null && t.noteReplace.regex != null) {
            sb.append("noteReplace(/").append(t.noteReplace.regex).append("/→")
                    .append(nz(t.noteReplace.with)).append(")");
        }
        return sb.toString().trim();
    }
    private static String nz(String s){ return s==null? "":s; }
}