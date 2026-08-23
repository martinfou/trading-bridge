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

public class DepthMktDataDescription {
    private String 	m_exchange;
    private String 	m_secType;
    private String 	m_listingExch;
    private String 	m_serviceDataType;
    private int 	m_aggGroup;

    // Get
    public String exchange() { return m_exchange; }
    public String secType() { return m_secType; }
    public String listingExch() { return m_listingExch; }
    public String serviceDataType() { return m_serviceDataType; }
    public int aggGroup() { return m_aggGroup; }

    // Set 
    public void exchange(String exchange) { m_exchange = exchange; }
    public void secType(String secType) { m_secType = secType; }
    public void listingExch(String listingExch) { m_listingExch = listingExch; }
    public void serviceDataType(String serviceDataType) { m_serviceDataType = serviceDataType; }
    public void aggGroup(int aggGroup) { m_aggGroup = aggGroup; }

    public DepthMktDataDescription() {
    }

    public DepthMktDataDescription(String p_exchange, String p_secType, String listingExch, String serviceDataType, int aggGroup) {
        m_exchange = p_exchange;
        m_secType = p_secType;
        m_listingExch = listingExch;
        m_serviceDataType = serviceDataType;
        m_aggGroup = aggGroup;
    }
}
