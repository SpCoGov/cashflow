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
package top.spco.cashflow.service;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import top.spco.cashflow.data.CategoryTaxonomy;
import top.spco.cashflow.model.RecordRow;

import java.util.*;

public final class TaxonomyService {
    private CategoryTaxonomy taxonomy =
            new CategoryTaxonomy(new ArrayList<>(), new ArrayList<>());

    // 暴露可观察候选
    private final ObservableList<String> categories = FXCollections.observableArrayList();
    private final Map<String, ObservableList<String>> subsByCat = new HashMap<>();

    public void setTaxonomy(CategoryTaxonomy t) {
        if (t == null) t = new CategoryTaxonomy(new ArrayList<>(), new ArrayList<>());
        this.taxonomy = t;
        rebuildChoices();
    }

    public CategoryTaxonomy getTaxonomy() {
        return taxonomy;
    }

    // 下拉候选
    public ObservableList<String> categories() {
        return categories;
    }

    public ObservableList<String> subsOf(String cat) {
        return subsByCat.getOrDefault(cat, FXCollections.observableArrayList());
    }

    // 并入新类别/子类
    public void ensure(String cat, String sub) {
        String c = safe(cat), s = safe(sub);
        if (c.isEmpty() || s.isEmpty()) return;
        int catId = taxonomy.categoryIdOf(c); // 不存在则追加
        taxonomy.subIdOf(catId, s);           // 不存在则追加
        rebuildChoices();                     // 刷新候选
    }

    // 工具：是否有记录引用（供删除校验）
    public boolean isCategoryUsed(List<RecordRow> rows, String cat) {
        String c = safe(cat);
        if (c.isEmpty()) return false;
        for (RecordRow r : rows) if (c.equals(r.getCategory())) return true;
        return false;
    }

    public boolean isSubUsed(List<RecordRow> rows, String cat, String sub) {
        String c = safe(cat), s = safe(sub);
        if (c.isEmpty() || s.isEmpty()) return false;
        for (RecordRow r : rows) if (c.equals(r.getCategory()) && s.equals(r.getSubCategory())) return true;
        return false;
    }

    // 从行兜底构建 taxonomy（和你原来的逻辑一致）
    public static CategoryTaxonomy buildFromRows(List<RecordRow> rows) {
        Map<String, Set<String>> map = new TreeMap<>();
        for (RecordRow r : rows) {
            String c = safe(r.getCategory());
            String s = safe(r.getSubCategory());
            if (c.isEmpty() || s.isEmpty()) continue;
            map.computeIfAbsent(c, k -> new TreeSet<>()).add(s);
        }
        List<String> cats = new ArrayList<>(map.keySet());
        List<List<String>> subs = new ArrayList<>();
        for (String c : cats) subs.add(new ArrayList<>(map.get(c)));
        return new CategoryTaxonomy(cats, subs);
    }

    // ===== 内部 =====
    private void rebuildChoices() {
        categories.setAll(taxonomy.categories());
        subsByCat.clear();
        for (String c : taxonomy.categories()) {
            subsByCat.put(c, FXCollections.observableArrayList(taxonomy.subsOf(c)));
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }
}