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
package top.spco.cashflow.viewmodel;

import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import top.spco.cashflow.model.RecordRow;

import java.io.File;
import java.time.YearMonth;

public final class LedgerViewModel {
    private final ObservableList<RecordRow> entries = FXCollections.observableArrayList();
    private final ObjectProperty<YearMonth> yearMonth = new SimpleObjectProperty<>(YearMonth.now());
    private final ObjectProperty<File> currentFile = new SimpleObjectProperty<>();
    private final BooleanProperty dirty = new SimpleBooleanProperty(false);

    public ObservableList<RecordRow> getEntries() { return entries; }
    public ObjectProperty<YearMonth> yearMonthProperty() { return yearMonth; }
    public ObjectProperty<File> currentFileProperty() { return currentFile; }
    public BooleanProperty dirtyProperty() { return dirty; }

    public YearMonth getYearMonth() { return yearMonth.get(); }
    public void setYearMonth(YearMonth ym) { yearMonth.set(ym); }

    public File getCurrentFile() { return currentFile.get(); }
    public void setCurrentFile(File f) { currentFile.set(f); }

    public boolean isDirty() { return dirty.get(); }
    public void markDirty() { dirty.set(true); }
    public void clearDirty() { dirty.set(false); }
}