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

public class HistoricalTickBidAsk {
    private long m_time;
    private TickAttribBidAsk m_tickAttribBidAsk;
    private double m_priceBid;
    private double m_priceAsk;
    private Decimal m_sizeBid;
    private Decimal m_sizeAsk;

    public HistoricalTickBidAsk(long time, TickAttribBidAsk tickAttribBidAsk, double priceBid, double priceAsk, Decimal sizeBid, Decimal sizeAsk) {
        m_time = time;
        m_tickAttribBidAsk = tickAttribBidAsk;
        m_priceBid = priceBid;
        m_priceAsk = priceAsk;
        m_sizeBid = sizeBid;
        m_sizeAsk = sizeAsk;
    }

    public long time() {
        return m_time;
    }

    public TickAttribBidAsk tickAttribBidAsk() {
        return m_tickAttribBidAsk;
    }

    public double priceBid() {
        return m_priceBid;
    }

    public double priceAsk() {
        return m_priceAsk;
    }

    public Decimal sizeBid() {
        return m_sizeBid;
    }

    public Decimal sizeAsk() {
        return m_sizeAsk;
    }

}