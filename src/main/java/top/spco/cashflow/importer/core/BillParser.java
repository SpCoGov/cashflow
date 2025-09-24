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

import java.io.File;
import java.io.IOException;
import java.util.List;

public interface BillParser {
    /**
     * 是否支持此文件（根据扩展名/文件头等）。
     */
    boolean supports(File file);

    /**
     * 解析为统一模型列表（不做归类、不丢弃）。
     */
    List<UnifiedTxn> parse(File file) throws IOException;
}