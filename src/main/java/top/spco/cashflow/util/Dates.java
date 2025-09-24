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
package top.spco.cashflow.util;

import java.time.*;
import java.time.format.DateTimeFormatter;

public final class Dates {
    public static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static String formatDateTime(long epochMs) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault()).format(DTF);
    }

    public static long epochMsOf(YearMonth ym, int day, LocalTime time) {
        return ym.atDay(day).atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    public static void validateDayInMonth(YearMonth ym, int day) {
        int max = ym.lengthOfMonth();
        if (day < 1 || day > max)
            throw new IllegalArgumentException("非法日：" + day + "。该月应为 1.." + max);
    }

    public static LocalDate toLocalDate(long epochMs) {
        return Instant.ofEpochMilli(epochMs)
                .atZone(ZoneId.systemDefault())
                .toLocalDate();
    }


    public static LocalDate toLocalDate(long epochMs, ZoneId zone) {
        return Instant.ofEpochMilli(epochMs)
                .atZone(zone)
                .toLocalDate();
    }

    public static long startOfDayMillis(LocalDate date) {
        return date.atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();
    }

    private Dates() {
    }
}