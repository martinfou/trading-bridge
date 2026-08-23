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

public class IneligibilityReason {
    private String m_id;
    private String m_description;

    // Get
    public String id()          { return m_id; }
    public String description() { return m_description; }

    // Set 
    public void id(String id)                   { m_id = id; }
    public void description(String description) { m_description = description; }

    public IneligibilityReason() { 
    }

    public IneligibilityReason(String p_id, String p_description) {
        m_id = p_id;
        m_description = p_description;
    }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[id: ").append(m_id);
        sb.append(", description: ").append(m_description);
        sb.append("]");
        return sb.toString();
    }
}
