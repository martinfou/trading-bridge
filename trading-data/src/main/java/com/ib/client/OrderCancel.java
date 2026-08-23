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

public class OrderCancel {
    final public static String EMPTY_STR = "";

    private String m_manualOrderCancelTime;
    private String m_extOperator;
    private int m_manualOrderIndicator;

    // getters
    public String manualOrderCancelTime() { return m_manualOrderCancelTime; }
    public String extOperator()           { return m_extOperator; }
    public int manualOrderIndicator()     { return m_manualOrderIndicator; }

    // setters
    public void manualOrderCancelTime(String v) { m_manualOrderCancelTime = v; }
    public void extOperator(String v)           { m_extOperator = v; }
    public void manualOrderIndicator(int v)     { m_manualOrderIndicator = v; }

    public OrderCancel() {
        this(EMPTY_STR); 
    }

    public OrderCancel(String manualOrderCancelTime) {
        this(manualOrderCancelTime, EMPTY_STR, Integer.MAX_VALUE);
    }

    public OrderCancel(String manualOrderCancelTime, String extOperator, int manualOrderIndicator) {
        m_manualOrderCancelTime = manualOrderCancelTime;
        m_extOperator = extOperator;
        m_manualOrderIndicator = manualOrderIndicator;
    }

    @Override
    public boolean equals(Object p_other) {
        if (this == p_other) {
            return true;
        }
        if (!(p_other instanceof OrderCancel)) {
            return false;
        }
        OrderCancel l_theOther = (OrderCancel)p_other;

        if (Util.StringCompare(m_manualOrderCancelTime, l_theOther.m_manualOrderCancelTime) != 0 ||
            Util.StringCompare(m_extOperator, l_theOther.m_extOperator) != 0
            ) {
            return false;
        }

        if (m_manualOrderIndicator != l_theOther.m_manualOrderIndicator
            ) {
            return false;
        }

        return true;
    }
}
