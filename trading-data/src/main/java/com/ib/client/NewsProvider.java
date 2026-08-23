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

public class NewsProvider {
    private String 	m_providerCode;
    private String 	m_providerName;

    // Get
    public String providerCode() { return m_providerCode; }
    public String providerName() { return m_providerName; }

    // Set 
    public void providerCode(String providerCode) { m_providerCode = providerCode; }
    public void providerName(String providerName) { m_providerName = providerName; }

    public NewsProvider() {
    }

    public NewsProvider(String p_providerCode, String p_providerName) {
        m_providerCode = p_providerCode;
        m_providerName = p_providerName;
    }
}
