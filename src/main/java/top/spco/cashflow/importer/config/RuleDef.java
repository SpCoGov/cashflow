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

public final class RuleDef {
    public String name;
    public When when = new When();
    public Then then = new Then();

    public static final class When {
        public TextMatch payee;
        public TextMatch item;
        public TextMatch note;
        public String amount; // 例：">0", "<=123.45"
    }

    public static final class Then {
        public Boolean drop;          // true=丢弃
        public String category;       // 归类
        public String sub;            // 子类
        public NoteReplace noteReplace; // 正则替换备注
    }

    public static final class TextMatch {
        public String contains;
        public String equals;
        public String regex;
        public Boolean ignoreCase; // 默认 true
    }

    public static final class NoteReplace {
        public String regex;
        public String with;
    }
}