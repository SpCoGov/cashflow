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

import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.function.IntPredicate;

/**
 * 月度账本（表头 + 多条收支，SoA 列式存储）
 * 金额单位用“分”（正=收入，负=支出）
 */
public final class MonthlyLedger {
    // 表头
    final int year;
    final int month; // 1..12

    // 行数与容量
    int size;
    int capacity;

    // 列式存储（SoA）
    long[] ts;          // 时间戳 epoch millis
    long[] amount;      // 金额（分，正=入，负=出）
    int[] cat;          // 分类ID
    int[] subcat;       // 子类ID
    int[] noteOff;      // 备注blob中的偏移
    int[] noteLen;      // 备注长度

    // 备注统一存放的连续字节块（滚动写入）
    byte[] noteBlob;
    int noteSize;

    // —— 可选：分类快速索引（倒排链表），按需启用 —— //
    // head[catId] = 链表首行index 或 -1； next[i] = 同分类下的下一行index 或 -1
    int[] catHead;
    int[] catNext;
    boolean categoryIndexEnabled = false;

    public MonthlyLedger(int year, int month, int initialCapacity, int initialNoteBlob) {
        if (month < 1 || month > 12) throw new IllegalArgumentException("month must be 1..12");
        this.year = year;
        this.month = month;

        this.capacity = Math.max(8, initialCapacity);
        this.ts = new long[capacity];
        this.amount = new long[capacity];
        this.cat = new int[capacity];
        this.subcat = new int[capacity];
        this.noteOff = new int[capacity];
        this.noteLen = new int[capacity];

        this.noteBlob = new byte[Math.max(128, initialNoteBlob)];
        this.noteSize = 0;

        this.size = 0;
    }

    public static MonthlyLedger of(YearMonth ym, int capacity, int noteBlobBytes) {
        return new MonthlyLedger(ym.getYear(), ym.getMonthValue(), capacity, noteBlobBytes);
    }

    public int year() {
        return year;
    }

    public int month() {
        return month;
    }

    public int size() {
        return size;
    }

    /**
     * 启用按分类的倒排链表索引（若分类空间很大，可在外侧控制最大catId以配置数组大小）
     */
    public void enableCategoryIndex(int maxCategoryIdExclusive) {
        this.catHead = new int[maxCategoryIdExclusive];
        Arrays.fill(this.catHead, -1);
        this.catNext = new int[Math.max(8, capacity)];
        Arrays.fill(this.catNext, -1);
        this.categoryIndexEnabled = true;

        // 现有数据回建索引
        for (int i = 0; i < size; i++) linkCategoryIndex(i);
    }

    private void ensureRowCapacity() {
        if (size < capacity) return;
        int newCap = capacity + (capacity >>> 1); // x1.5
        ts = Arrays.copyOf(ts, newCap);
        amount = Arrays.copyOf(amount, newCap);
        cat = Arrays.copyOf(cat, newCap);
        subcat = Arrays.copyOf(subcat, newCap);
        noteOff = Arrays.copyOf(noteOff, newCap);
        noteLen = Arrays.copyOf(noteLen, newCap);
        if (categoryIndexEnabled) {
            catNext = Arrays.copyOf(catNext, newCap);
            Arrays.fill(catNext, capacity, newCap, -1);
        }
        capacity = newCap;
    }

    private void ensureNoteCapacity(int needMore) {
        int need = noteSize + needMore;
        if (need <= noteBlob.length) return;
        int newCap = noteBlob.length;
        while (newCap < need) newCap = newCap + (newCap >>> 1); // x1.5
        noteBlob = Arrays.copyOf(noteBlob, newCap);
    }

    /**
     * 追加一条记录（content 可为 null 或 ""）
     */
    public int add(long epochMillis, long amountInCents, int categoryId, int subCategoryId, String content) {
        ensureRowCapacity();
        byte[] bytes = (content == null || content.isEmpty()) ? null : content.getBytes(StandardCharsets.UTF_8);
        int off = noteSize;
        int len = (bytes == null) ? 0 : bytes.length;
        if (len > 0) {
            ensureNoteCapacity(len);
            System.arraycopy(bytes, 0, noteBlob, noteSize, len);
            noteSize += len;
        }

        int row = size++;
        ts[row] = epochMillis;
        amount[row] = amountInCents;
        cat[row] = categoryId;
        subcat[row] = subCategoryId;
        noteOff[row] = off;
        noteLen[row] = len;

        if (categoryIndexEnabled) linkCategoryIndex(row);
        return row;
    }

    private void linkCategoryIndex(int row) {
        int c = cat[row];
        // 单向头插
        int head = catHead[c < catHead.length ? c : 0]; // 粗暴防御：越界分类都挂到0类上（也可以选择抛异常/扩容catHead）
        catNext[row] = head;
        if (c < catHead.length) {
            catHead[c] = row;
        } else {
            catHead[0] = row; // 参见上行注释
        }
    }

    /**
     * 获取行的轻量只读视图（字符串在读取时即时解码，避免常驻String）
     */
    public EntryView get(int row) {
        rangeCheck(row);
        return new EntryView(row);
    }

    /**
     * 遍历满足条件的行（按自然顺序0..size-1），consumer返回true表示继续，false表示提前停止
     */
    public void forEach(IntPredicate rowPredicate) {
        for (int i = 0; i < size; i++) {
            if (!rowPredicate.test(i)) break;
        }
    }

    /**
     * 按分类快速遍历（需 enableCategoryIndex）
     */
    public void forEachByCategory(int categoryId, IntPredicate rowPredicate) {
        if (!categoryIndexEnabled) {
            // 降级为全表扫描
            forEach(i -> (cat[i] == categoryId) && rowPredicate.test(i) || cat[i] != categoryId);
            return;
        }
        if (categoryId < 0 || categoryId >= catHead.length) return;
        int r = catHead[categoryId];
        while (r != -1) {
            if (!rowPredicate.test(r)) break;
            r = catNext[r];
        }
    }

    /**
     * 根据时间范围求和（闭区间）
     */
    public long sumAmountByTimeRange(long fromMillisInclusive, long toMillisInclusive) {
        long s = 0;
        for (int i = 0; i < size; i++) {
            long t = ts[i];
            if (t >= fromMillisInclusive && t <= toMillisInclusive) {
                s += amount[i];
            }
        }
        return s;
    }

    /**
     * 返回按时间戳升序的“行号视图”数组，不改动底层数据
     */
    public int[] sortedIndicesByTimestampAsc() {
        int[] idx = new int[size];
        for (int i = 0; i < size; i++) idx[i] = i;
        // TimSort on primitive via boxed会有装箱成本；这里用简易快排以避免装箱
        quickSortByTs(idx, 0, size - 1);
        return idx;
    }

    private void quickSortByTs(int[] a, int l, int r) {
        if (l >= r) return;
        int i = l, j = r;
        long pivot = ts[a[(l + r) >>> 1]];
        while (i <= j) {
            while (ts[a[i]] < pivot) i++;
            while (ts[a[j]] > pivot) j--;
            if (i <= j) {
                int tmp = a[i];
                a[i] = a[j];
                a[j] = tmp;
                i++;
                j--;
            }
        }
        if (l < j) quickSortByTs(a, l, j);
        if (i < r) quickSortByTs(a, i, r);
    }

    public final class EntryView {
        private final int row;

        private EntryView(int row) {
            this.row = row;
        }

        public int row() {
            return row;
        }

        public long timestamp() {
            return ts[row];
        }

        public long amountInCents() {
            return amount[row];
        }

        public int categoryId() {
            return cat[row];
        }

        public int subCategoryId() {
            return subcat[row];
        }

        public String noteUtf8() {
            int off = noteOff[row], len = noteLen[row];
            if (len == 0) return "";
            return new String(noteBlob, off, len, StandardCharsets.UTF_8);
        }
    }

    private void rangeCheck(int r) {
        if (r < 0 || r >= size) throw new NoSuchElementException("row " + r);
    }
}


