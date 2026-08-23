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

public class TickByTick {
    private int m_tickType; // 0 - None, 1 - Last, 2 - AllLast, 3 -BidAsk, 4 - MidPoint
    private long m_time;  // in seconds
    private double m_price;
    private Decimal m_size;
    private TickAttribLast m_tickAttribLast;
    private TickAttribBidAsk m_tickAttribBidAsk;
    private String m_exchange;
    private String m_specialConditions;
    private double m_bidPrice;
    private Decimal m_bidSize;
    private double m_askPrice;
    private Decimal m_askSize;
    private double m_midPoint;

    public TickByTick(int tickType, long time, double price, Decimal size, TickAttribLast tickAttribLast, String exchange, String specialConditions) {
    	m_tickType = tickType;
        m_time = time;
        m_price = price;
        m_size = size;
        m_tickAttribLast = tickAttribLast;
        m_exchange = exchange;
        m_specialConditions = specialConditions;
    }

    public TickByTick(long time, double bidPrice, Decimal bidSize, double askPrice, Decimal askSize, TickAttribBidAsk tickAttribBidAsk) {
    	m_tickType = 3;
        m_time = time;
        m_bidPrice = bidPrice;
        m_bidSize = bidSize;
        m_askPrice = askPrice;
        m_askSize = askSize;
        m_tickAttribBidAsk = tickAttribBidAsk;
    }

    public TickByTick(long time, double midPoint) {
    	m_tickType = 4;
        m_time = time;
        m_midPoint = midPoint;
    }
    
    public int tickType() {
        return m_tickType;
    }
    
    public long time() {
        return m_time;
    }

    public double price() {
        return m_price;
    }

    public Decimal size() {
        return m_size;
    }

    public TickAttribLast tickAttribLast() {
    	return m_tickAttribLast;
    }

    public TickAttribBidAsk tickAttribBidAsk() {
    	return m_tickAttribBidAsk;
    }
    
    public String tickAttribLastStr() {
        StringBuilder sb = new StringBuilder();
        sb.append(m_tickAttribLast.pastLimit() ? "PastLimit " : "");
        sb.append(m_tickAttribLast.unreported() ? "Unreported " : "");
        return sb.toString();
    }

    public String tickAttribBidAskStr() {
        StringBuilder sb = new StringBuilder();
        sb.append(m_tickAttribBidAsk.bidPastLow() ? "BidPastLow " : "");
        sb.append(m_tickAttribBidAsk.askPastHigh() ? "AskPastHigh " : "");
        return sb.toString();
    }
    
    public String exchange() {
        return m_exchange;
    }

    public String specialConditions() {
        return m_specialConditions;
    }

    public double bidPrice() {
        return m_bidPrice;
    }

    public Decimal bidSize() {
        return m_bidSize;
    }
    
    public double askPrice() {
        return m_askPrice;
    }

    public Decimal askSize() {
        return m_askSize;
    }

    public double midPoint() {
        return m_midPoint;
    }
}