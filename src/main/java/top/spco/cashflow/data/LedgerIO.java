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

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class LedgerIO {
    private static final int MAGIC = 0x4D4C4432; // 'MLD2'
    private static final int VERSION = 2;

    public static void save(MonthlyLedger ledger, CategoryTaxonomy taxonomy, File file) throws IOException {
        Objects.requireNonNull(ledger);
        Objects.requireNonNull(taxonomy);
        if (taxonomy.categoryCount() == 0) throw new IOException("至少需要一个类别");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);

            out.writeInt(ledger.year());
            out.writeInt(ledger.month());
            out.writeInt(ledger.size());

            for (int i = 0; i < ledger.size(); i++) out.writeLong(ledger.ts[i]);
            for (int i = 0; i < ledger.size(); i++) out.writeLong(ledger.amount[i]);
            for (int i = 0; i < ledger.size(); i++) out.writeInt(ledger.cat[i]);
            for (int i = 0; i < ledger.size(); i++) out.writeInt(ledger.subcat[i]);
            for (int i = 0; i < ledger.size(); i++) out.writeInt(ledger.noteOff[i]);
            for (int i = 0; i < ledger.size(); i++) out.writeInt(ledger.noteLen[i]);

            out.writeInt(ledger.noteSize);
            out.write(ledger.noteBlob, 0, ledger.noteSize);

            // 分类树
            int C = taxonomy.categoryCount();
            out.writeInt(C);
            for (int c = 0; c < C; c++) {
                out.writeUTF(taxonomy.categoryName(c));
                int S = taxonomy.subCount(c);
                if (S == 0) throw new IOException("类别【" + taxonomy.categoryName(c) + "】至少需要一个子类别");
                out.writeInt(S);
                for (int s = 0; s < S; s++) {
                    out.writeUTF(taxonomy.subName(c, s));
                }
            }
        }
    }

    public static Bundle load(File file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            int magic = in.readInt();
            if (magic != MAGIC) throw new IOException("非法文件头");
            int ver = in.readInt();
            if (ver != VERSION) throw new IOException("不支持的版本: " + ver);

            int year = in.readInt();
            int month = in.readInt();
            int size = in.readInt();

            MonthlyLedger ledger = new MonthlyLedger(year, month, size, 4096);
            for (int i = 0; i < size; i++) ledger.ts[i] = in.readLong();
            for (int i = 0; i < size; i++) ledger.amount[i] = in.readLong();
            for (int i = 0; i < size; i++) ledger.cat[i] = in.readInt();
            for (int i = 0; i < size; i++) ledger.subcat[i] = in.readInt();
            for (int i = 0; i < size; i++) ledger.noteOff[i] = in.readInt();
            for (int i = 0; i < size; i++) ledger.noteLen[i] = in.readInt();

            int blobSize = in.readInt();
            ledger.noteBlob = new byte[Math.max(blobSize, 128)];
            in.readFully(ledger.noteBlob, 0, blobSize);
            ledger.noteSize = blobSize;
            ledger.size = size;

            // 分类树
            int C = in.readInt();
            if (C <= 0) throw new IOException("文件中未包含任何类别");
            List<String> cats = new ArrayList<>(C);
            List<List<String>> subs = new ArrayList<>(C);
            for (int c = 0; c < C; c++) {
                String catName = in.readUTF();
                cats.add(catName);
                int S = in.readInt();
                if (S <= 0) throw new IOException("类别【" + catName + "】没有子类别");
                List<String> subList = new ArrayList<>(S);
                for (int s = 0; s < S; s++) subList.add(in.readUTF());
                subs.add(subList);
            }
            CategoryTaxonomy taxonomy = new CategoryTaxonomy(cats, subs);
            return new Bundle(ledger, taxonomy);
        }
    }

    public static final class Bundle {
        public final MonthlyLedger ledger;
        public final CategoryTaxonomy taxonomy;

        public Bundle(MonthlyLedger l, CategoryTaxonomy t) {
            this.ledger = l;
            this.taxonomy = t;
        }
    }
}