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
package top.spco.cashflow.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class CategoryTaxonomy {
    private final List<String> categories;
    private final List<List<String>> subs; // 与 categories 对齐

    public CategoryTaxonomy(List<String> categories, List<List<String>> subs) {
        this.categories = new ArrayList<>(Objects.requireNonNull(categories));
        this.subs = new ArrayList<>(Objects.requireNonNull(subs));
        if (this.categories.size() != this.subs.size())
            throw new IllegalArgumentException("分类与子分类数量不匹配");
    }

    public int categoryCount() {
        return categories.size();
    }

    public int subCount(int catId) {
        return subs.get(catId).size();
    }

    public String categoryName(int catId) {
        return categories.get(catId);
    }

    public String subName(int catId, int subId) {
        return subs.get(catId).get(subId);
    }

    public List<String> categories() {
        return new ArrayList<>(categories);
    }

    public List<String> subsOf(String category) {
        int catId = categoryIdOf(category);
        if (catId < 0) return List.of();
        return new ArrayList<>(subs.get(catId));
    }

    public int categoryIdOf(String name) {
        for (int i = 0; i < categories.size(); i++) if (categories.get(i).equals(name)) return i;
        // 不存在则追加一个新类别（调用处需保证至少有一个子类再保存）
        categories.add(name);
        subs.add(new ArrayList<>());
        return categories.size() - 1;
    }

    public int subIdOf(int catId, String subName) {
        List<String> list = subs.get(catId);
        for (int i = 0; i < list.size(); i++) if (list.get(i).equals(subName)) return i;
        list.add(subName);
        return list.size() - 1;
    }
}