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
import top.spco.cashflow.importer.wechat.WeChatBillParser;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * 统一入口：选择 Parser -> 规则引擎 -> FinalTxn 列表。
 */
public final class BillImporterService {
    private final List<BillParser> parsers = List.of(
            new WeChatBillParser()
    );

    public List<FinalTxn> importFile(File file, RuleConfig cfg) throws IOException {
        BillParser p = parsers.stream().filter(pp -> pp.supports(file)).findFirst()
                .orElseThrow(() -> new IOException("没有可用的解析器：" + file.getName()));
        List<UnifiedTxn> raw = p.parse(file);
        return RuleEngine.apply(raw, cfg);
    }
}