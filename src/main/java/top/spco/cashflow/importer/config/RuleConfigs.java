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
package top.spco.cashflow.importer.config;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class RuleConfigs {
    public static RuleConfig load(File yamlFile) throws IOException {
        try (var r = new InputStreamReader(new FileInputStream(yamlFile), StandardCharsets.UTF_8)) {
            return new Yaml().loadAs(r, RuleConfig.class);
        }
    }

    public static RuleConfig empty() {
        return new RuleConfig();
    }

    /**
     * 以“纯 Map”方式导出：无 !!class 标签、无 null，并可控制键顺序
     */
    public static void save(File f, RuleConfig cfg) throws IOException {
        // 1) 组装一个“视图”Map来控制顺序与省略空值
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", cfg.version);

        // defaults
        if (cfg.defaults != null) {
            Map<String, Object> d = new LinkedHashMap<>();
            if (notBlank(cfg.defaults.category)) d.put("category", cfg.defaults.category);
            if (notBlank(cfg.defaults.sub)) d.put("sub", cfg.defaults.sub);
            if (cfg.defaults.noteFallbackOrder != null && !cfg.defaults.noteFallbackOrder.isEmpty()) {
                d.put("noteFallbackOrder", cfg.defaults.noteFallbackOrder);
            }
            if (cfg.defaults.onlyAppendFromLastDate) {
                d.put("onlyAppendFromLastDate", true); // 只在 true 时写出
            }
            if (!d.isEmpty()) root.put("defaults", d);
        }

        // rules
        List<Map<String, Object>> rules = new ArrayList<>();
        if (cfg.rules != null) {
            for (RuleDef r : cfg.rules) {
                Map<String, Object> rMap = new LinkedHashMap<>();
                if (notBlank(r.name)) rMap.put("name", r.name);

                // when 先
                Map<String, Object> when = new LinkedHashMap<>();
                if (r.when != null) {
                    if (r.when.payee != null) {
                        Map<String, Object> m = textMatchMap(r.when.payee);
                        if (!m.isEmpty()) when.put("payee", m);
                    }
                    if (r.when.item != null) {
                        Map<String, Object> m = textMatchMap(r.when.item);
                        if (!m.isEmpty()) when.put("item", m);
                    }
                    if (r.when.note != null) {
                        Map<String, Object> m = textMatchMap(r.when.note);
                        if (!m.isEmpty()) when.put("note", m);
                    }
                    if (notBlank(r.when.amount)) when.put("amount", r.when.amount);
                }
                if (!when.isEmpty()) rMap.put("when", when);

                // then 后
                Map<String, Object> then = new LinkedHashMap<>();
                if (r.then != null) {
                    if (Boolean.TRUE.equals(r.then.drop)) then.put("drop", true);
                    if (notBlank(r.then.category)) then.put("category", r.then.category);
                    if (notBlank(r.then.sub)) then.put("sub", r.then.sub);
                    if (r.then.noteReplace != null && notBlank(r.then.noteReplace.regex)) {
                        Map<String, Object> nr = new LinkedHashMap<>();
                        nr.put("regex", r.then.noteReplace.regex);
                        nr.put("with", nz(r.then.noteReplace.with));
                        then.put("noteReplace", nr);
                    }
                }
                if (!then.isEmpty()) rMap.put("then", then);

                rules.add(rMap);
            }
        }
        root.put("rules", rules);

        // 2) 正确配置 DumperOptions（关键：indicatorIndent < indent）
        DumperOptions opt = new DumperOptions();
        opt.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opt.setPrettyFlow(true);
        opt.setIndent(2);
        opt.setIndicatorIndent(1);
        opt.setIndentWithIndicator(true);
        opt.setDefaultScalarStyle(DumperOptions.ScalarStyle.PLAIN);


        // 3) Dump（Map/List 不会出现 !!类名；我们已手动剔除了空值）
        Yaml yaml = new Yaml(opt);
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            yaml.dump(root, w);
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static Map<String, Object> textMatchMap(RuleDef.TextMatch tm) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (tm == null) return m;
        if (notBlank(tm.contains)) m.put("contains", tm.contains);
        if (notBlank(tm.equals)) m.put("equals", tm.equals);
        if (notBlank(tm.regex)) m.put("regex", tm.regex);
        // ignoreCase 默认 true；只有明确为 false 时才写出
        if (Boolean.FALSE.equals(tm.ignoreCase)) m.put("ignoreCase", false);
        return m;
    }
}