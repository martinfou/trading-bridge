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

public class FamilyCode {
    private String 	m_accountID;
    private String 	m_familyCodeStr;

    // Get
    public String accountID() { return m_accountID; }
    public String familyCodeStr() { return m_familyCodeStr; }

    // Set 
    public void accountID(String accountID) { m_accountID = accountID; }
    public void familyCodeStr(String familyCodeStr) { m_familyCodeStr = familyCodeStr; }
    
    public FamilyCode() {
    }

    public FamilyCode(String p_accountID, String p_familyCodeStr) {
        m_accountID = p_accountID;
        m_familyCodeStr = p_familyCodeStr;
    }
}
