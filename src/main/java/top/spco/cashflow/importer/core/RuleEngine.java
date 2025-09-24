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
package top.spco.cashflow.importer.core;

import top.spco.cashflow.importer.config.RuleConfig;
import top.spco.cashflow.importer.config.RuleDef;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class RuleEngine {

    public static List<FinalTxn> apply(List<UnifiedTxn> src, RuleConfig cfg) {
        List<FinalTxn> out = new ArrayList<>();
        for (UnifiedTxn t : src) {
            RuleDef.Then then = null;

            // 顺序匹配，命中首个规则
            for (RuleDef rule : cfg.rules) {
                if (matches(t, rule.when)) {
                    then = rule.then;
                    break;
                }
            }

            if (then != null && Boolean.TRUE.equals(then.drop)) {
                continue; // 丢弃
            }

            String cat = (then != null && notBlank(then.category)) ? then.category : cfg.defaults.category;
            String sub = (then != null && notBlank(then.sub)) ? then.sub : cfg.defaults.sub;

            String note = t.note();
            if (then != null && then.noteReplace != null && notBlank(then.noteReplace.regex)) {
                note = note.replaceAll(then.noteReplace.regex, then.noteReplace.with == null ? "" : then.noteReplace.with);
            }
            if (isBlank(note)) note = fallbackNote(t, cfg);

            out.add(new FinalTxn(t.timestampMs(), t.amountCents(), cat, sub, note));
        }
        return out;
    }

    // ---------- helpers ----------
    private static boolean matches(UnifiedTxn t, RuleDef.When w) {
        if (w == null) return true;
        if (w.payee != null && !textMatch(t.payee(), w.payee)) return false;
        if (w.item != null && !textMatch(t.item(), w.item)) return false;
        if (w.note != null && !textMatch(t.note(), w.note)) return false;
        if (w.amount != null && !amountMatch(t.amountCents(), w.amount)) return false;

        return true;
    }

    private static boolean textMatch(String text, RuleDef.TextMatch tm) {
        String s = nz(text);
        boolean ic = tm.ignoreCase == null || tm.ignoreCase;
        String cmp = ic ? s.toLowerCase() : s;

        if (tm.equals != null) {
            String target = ic ? tm.equals.toLowerCase() : tm.equals;
            if (!cmp.equals(target)) return false;
        }
        if (tm.contains != null) {
            String needle = ic ? tm.contains.toLowerCase() : tm.contains;
            if (!cmp.contains(needle)) return false;
        }
        if (tm.regex != null) {
            Pattern p = Pattern.compile(tm.regex);
            if (!p.matcher(s).find()) return false;
        }
        return true;
    }

    // expr: >0, <0, >=123.45, ==0, != 10
    private static boolean amountMatch(long cents, String expr) {
        String e = expr.replace(" ", "");
        String op;
        if (e.startsWith(">=")) op = ">=";
        else if (e.startsWith("<=")) op = "<=";
        else if (e.startsWith("==")) op = "==";
        else if (e.startsWith("!=")) op = "!=";
        else if (e.startsWith(">")) op = ">";
        else if (e.startsWith("<")) op = "<";
        else throw new IllegalArgumentException("非法金额表达式: " + expr);
        String num = e.substring(op.length());
        long rhs = new BigDecimal(num).setScale(2).movePointRight(2).longValueExact();

        return switch (op) {
            case ">" -> cents > rhs;
            case "<" -> cents < rhs;
            case ">=" -> cents >= rhs;
            case "<=" -> cents <= rhs;
            case "==" -> cents == rhs;
            case "!=" -> cents != rhs;
            default -> false;
        };
    }

    private static String fallbackNote(UnifiedTxn t, RuleConfig cfg) {
        for (String k : cfg.defaults.noteFallbackOrder) {
            switch (k) {
                case "note" -> {
                    if (notBlank(t.note())) return t.note();
                }
                case "item" -> {
                    if (notBlank(t.item())) return t.item();
                }
                case "payee" -> {
                    if (notBlank(t.payee())) return t.payee();
                }
            }
        }
        return "";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}