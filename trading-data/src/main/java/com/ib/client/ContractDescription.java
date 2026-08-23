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

import java.util.Arrays;

public class ContractDescription {
    private Contract m_contract;
    private String[] m_derivativeSecTypes;

    // Get
    public Contract contract() {
        return m_contract;
    }

    public String[] derivativeSecTypes() {
        return (m_derivativeSecTypes == null) ? null : Arrays.copyOf(m_derivativeSecTypes, m_derivativeSecTypes.length);
    }

    // Set
    public void contract(Contract contract) {
        m_contract = contract;
    }

    public void derivativeSecTypes(String[] derivativeSecTypes) {
        if (derivativeSecTypes == null) {
            m_derivativeSecTypes = null;
        } else {
            m_derivativeSecTypes = Arrays.copyOf(derivativeSecTypes, derivativeSecTypes.length);
        }
    }

    public ContractDescription() {
        m_contract = new Contract();
    }

    public ContractDescription(Contract p_contract, String[] p_derivativeSecTypes) {
        m_contract = p_contract;
        if (p_derivativeSecTypes == null) {
            m_derivativeSecTypes = null;
        } else {
            m_derivativeSecTypes = Arrays.copyOf(p_derivativeSecTypes, p_derivativeSecTypes.length);
        }
    }

    @Override
    public String toString() {
        return "conid: " + m_contract.conid() + "\n"
                + "symbol: " + m_contract.symbol() + "\n"
                + "secType: " + m_contract.secType().toString() + "\n"
                + "primaryExch: " + m_contract.primaryExch() + "\n"
                + "currency: " + m_contract.currency() + "\n"
                + "description: " + m_contract.description() + "\n"
                + "issuerId: " + m_contract.issuerId() + "\n"
                + "derivativeSecTypes: " + Arrays.toString(m_derivativeSecTypes) + "\n";
    }
}
