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
package top.spco.cashflow.model;

import javafx.beans.property.*;

public final class RecordRow {
    private final LongProperty timestampMs = new SimpleLongProperty();
    private final LongProperty amountCents = new SimpleLongProperty();
    private final StringProperty category    = new SimpleStringProperty();
    private final StringProperty subCategory = new SimpleStringProperty();
    private final StringProperty note        = new SimpleStringProperty();

    public RecordRow(long tsMs, long cents, String cat, String sub, String note) {
        this.timestampMs.set(tsMs);
        this.amountCents.set(cents);
        this.category.set(cat == null ? "" : cat);
        this.subCategory.set(sub == null ? "" : sub);
        this.note.set(note == null ? "" : note);
    }

    public long getTimestampMs() { return timestampMs.get(); }
    public long getAmountCents() { return amountCents.get(); }
    public String getCategory()  { return category.get(); }
    public String getSubCategory(){ return subCategory.get(); }
    public String getNote()      { return note.get(); }

    public LongProperty timestampMsProperty() { return timestampMs; }
    public LongProperty amountCentsProperty() { return amountCents; }
    public StringProperty categoryProperty()  { return category; }
    public StringProperty subCategoryProperty(){ return subCategory; }
    public StringProperty noteProperty()      { return note; }
}
