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

public final class PojoDeepCopy {
    public static RuleDef copyRule(RuleDef s){
        if (s == null) return new RuleDef();
        RuleDef r = new RuleDef();
        r.name = s.name;

        r.when = new RuleDef.When();
        if (s.when != null){
            r.when.amount = s.when.amount;
            r.when.payee = copyTM(s.when.payee);
            r.when.item  = copyTM(s.when.item);
            r.when.note  = copyTM(s.when.note);
        }

        r.then = new RuleDef.Then();
        if (s.then != null){
            r.then.drop = s.then.drop;
            r.then.category = s.then.category;
            r.then.sub = s.then.sub;
            if (s.then.noteReplace != null){
                r.then.noteReplace = new RuleDef.NoteReplace();
                r.then.noteReplace.regex = s.then.noteReplace.regex;
                r.then.noteReplace.with = s.then.noteReplace.with;
            }
        }
        return r;
    }

    private static RuleDef.TextMatch copyTM(RuleDef.TextMatch t){
        if (t == null) return null;
        RuleDef.TextMatch c = new RuleDef.TextMatch();
        c.contains = t.contains;
        c.equals = t.equals;
        c.regex = t.regex;
        c.ignoreCase = t.ignoreCase;
        return c;
    }
}