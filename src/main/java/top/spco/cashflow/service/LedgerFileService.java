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

import top.spco.cashflow.data.LedgerIO;
import top.spco.cashflow.data.MonthlyLedger;
import top.spco.cashflow.model.RecordRow;
import top.spco.cashflow.viewmodel.LedgerViewModel;

import java.io.File;
import java.io.IOException;
import java.time.YearMonth;
import java.util.List;

public final class LedgerFileService {

    // 打开文件：灌入 VM & 分类服务
    public void open(File f, LedgerViewModel vm, TaxonomyService tax) throws IOException {
        LedgerIO.Bundle b = LedgerIO.load(f);
        vm.getEntries().clear();
        int[] order = b.ledger.sortedIndicesByTimestampAsc();
        for (int r : order) {
            var e = b.ledger.get(r);
            String cat = b.taxonomy.categoryName(e.categoryId());
            String sub = b.taxonomy.subName(e.categoryId(), e.subCategoryId());
            vm.getEntries().add(new RecordRow(e.timestamp(), e.amountInCents(), cat, sub, e.noteUtf8()));
        }
        vm.setYearMonth(YearMonth.of(b.ledger.year(), b.ledger.month()));
        vm.setCurrentFile(f);
        tax.setTaxonomy(b.taxonomy);
        vm.clearDirty();
    }

    // 保存（使用 VM.currentFile）——确保 taxonomy 覆盖所有行
    public void save(LedgerViewModel vm, TaxonomyService tax) throws IOException {
        File target = vm.getCurrentFile();
        if (target == null) throw new IllegalStateException("保存前请先指定文件");

        CategoryTaxonomyEnsureAll(vm.getEntries(), tax);

        YearMonth ym = vm.getYearMonth();
        List<RecordRow> rows = vm.getEntries();
        MonthlyLedger ledger = MonthlyLedger.of(ym, Math.max(32, rows.size()), Math.max(256, rows.size() * 16));
        for (RecordRow r : rows) {
            int catId = tax.getTaxonomy().categoryIdOf(r.getCategory());
            int subId = tax.getTaxonomy().subIdOf(catId, r.getSubCategory());
            ledger.add(r.getTimestampMs(), r.getAmountCents(), catId, subId, r.getNote());
        }
        LedgerIO.save(ledger, tax.getTaxonomy(), target);
        vm.clearDirty();
    }

    // 首次保存（Save As）用
    public void saveAs(File file, LedgerViewModel vm, TaxonomyService tax) throws IOException {
        vm.setCurrentFile(file);
        save(vm, tax);
    }

    private static void CategoryTaxonomyEnsureAll(List<RecordRow> rows, TaxonomyService tax) {
        if (tax.getTaxonomy() == null || tax.getTaxonomy().categoryCount() == 0) {
            tax.setTaxonomy(TaxonomyService.buildFromRows(rows));
        }
        for (RecordRow r : rows) {
            tax.ensure(r.getCategory(), r.getSubCategory());
        }
        if (tax.getTaxonomy().categoryCount() == 0)
            throw new IllegalStateException("文件必须至少包含一个类别");
        for (int c = 0; c < tax.getTaxonomy().categoryCount(); c++) {
            if (tax.getTaxonomy().subCount(c) == 0)
                throw new IllegalStateException("类别【" + tax.getTaxonomy().categoryName(c) + "】必须至少包含一个子类别");
        }
    }
}
