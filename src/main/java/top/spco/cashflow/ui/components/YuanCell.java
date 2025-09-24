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
package top.spco.cashflow.ui.components;

import javafx.scene.control.TableCell;
import javafx.util.StringConverter;
import top.spco.cashflow.util.Amounts;

public final class YuanCell<S> extends TableCell<S, Long> {
    public YuanCell() {
        super();
    }

    @Override
    public void updateItem(Long cents, boolean empty) {
        super.updateItem(cents, empty);
        if (empty || cents == null) {
            setText(null);
        } else {
            setText(Amounts.formatYuan(cents));
        }
    }

    private static final class LongStringConverterWithYuan extends StringConverter<Long> {
        @Override
        public String toString(Long cents) {
            return cents == null ? "" : Amounts.formatYuan(cents);
        }

        @Override
        public Long fromString(String yuan) {
            return top.spco.cashflow.util.Amounts.yuanToCents(yuan);
        }
    }
}