/*
 * Java TWS API Client
 *
 * Copyright (C) 2013-2026  Interactive Brokers LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.ib.client;

public enum OptionExerciseType {
    None(-1),
    Exercise(1),
    Lapse(2),
    DoNothing(3),
    Assigned(100),
    AutoexerciseClearing(101),
    Expired(102),
    Netting(103),
    AutoexerciseTrading(200);

    private int m_val;

    OptionExerciseType(int v) {
         m_val = v;
    }

    public int val() {
        return m_val;
    }

    public static OptionExerciseType fromInt(int n) {
        for (OptionExerciseType i : OptionExerciseType.values())
            if (i.val() == n)
                return i;

        throw new IllegalArgumentException("Error: " + n + " is not a valid value for enum OptionExerciseType");
    }
}