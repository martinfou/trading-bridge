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

public class WshEventData {

    private int m_conId;
    private String m_filter;
    private boolean m_fillWatchlist;
    private boolean m_fillPortfolio;
    private boolean m_fillCompetitors;
    private String m_startDate;
    private String m_endDate;
    private int m_totalLimit;

    public int conId() { return m_conId; }
    public String filter() { return m_filter; }
    public boolean fillWatchlist() { return m_fillWatchlist; }
    public boolean fillPortfolio() { return m_fillPortfolio; }
    public boolean fillCompetitors() { return m_fillCompetitors; }
    public String startDate() { return m_startDate; }
    public String endDate() { return m_endDate; }
    public int totalLimit() { return m_totalLimit; }

    public WshEventData(int conId, boolean fillWatchlist, boolean fillPortfolio, boolean fillCompetitors,
            String startDate, String endDate, int totalLimit) {
        m_conId = conId;
        m_filter = "";
        m_fillWatchlist = fillWatchlist;
        m_fillPortfolio = fillPortfolio;
        m_fillCompetitors = fillCompetitors;
        m_startDate = startDate;
        m_endDate = endDate;
        m_totalLimit = totalLimit;
    }

    public WshEventData(String filter, boolean fillWatchlist, boolean fillPortfolio, boolean fillCompetitors,
            String startDate, String endDate, int totalLimit) {
        m_conId = Integer.MAX_VALUE;
        m_filter = filter;
        m_fillWatchlist = fillWatchlist;
        m_fillPortfolio = fillPortfolio;
        m_fillCompetitors = fillCompetitors;
        m_startDate = startDate;
        m_endDate = endDate;
        m_totalLimit = totalLimit;
    }

}
