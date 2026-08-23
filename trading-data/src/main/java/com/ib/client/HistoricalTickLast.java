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

public class HistoricalTickLast {
    private long m_time;
    private TickAttribLast m_tickAttribLast;
    private double m_price;
    private Decimal m_size;
    private String m_exchange;
    private String m_specialConditions;

    public HistoricalTickLast(long time, TickAttribLast tickAttribLast, double price, Decimal size, String exchange, String specialConditions) {
        m_time = time;
        m_tickAttribLast = tickAttribLast;
        m_price = price;
        m_size = size;
        m_exchange = exchange;
        m_specialConditions = specialConditions;
    }

    public long time() {
        return m_time;
    }

    public TickAttribLast tickAttribLast() {
        return m_tickAttribLast;
    }
    
    public double price() {
        return m_price;
    }

    public Decimal size() {
        return m_size;
    }

    public String exchange() {
        return m_exchange;
    }

    public String specialConditions() {
        return m_specialConditions;
    }
}