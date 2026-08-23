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

public class CommissionAndFeesReport {

    private String m_execId;
    private double m_commissionAndFees;
    private String m_currency;
    private double m_realizedPNL;
    private double m_yield;
    private int    m_yieldRedemptionDate; // YYYYMMDD format

    public CommissionAndFeesReport() {
        m_commissionAndFees = 0;
        m_realizedPNL = 0;
        m_yield = 0;
        m_yieldRedemptionDate = 0;
    }

    @Override
    public boolean equals(Object p_other) {
        if (this == p_other) {
            return true;
        }
        if (!(p_other instanceof CommissionAndFeesReport)) {
            return false;
        }
        CommissionAndFeesReport l_theOther = (CommissionAndFeesReport)p_other;
        return m_execId.equals(l_theOther.m_execId);
    }

    @Override
    public int hashCode() {
        // Since equals() uses m_execId only, the hashCode should do as well.
        return m_execId == null ? 0 : m_execId.hashCode();
    }

	public String execId() {
		return m_execId;
	}

	public void execId(String execId) {
		this.m_execId = execId;
	}

	public double commissionAndFees() {
		return m_commissionAndFees;
	}

	public void commissionAndFees(double commissionAndFees) {
		this.m_commissionAndFees = commissionAndFees;
	}

	public String currency() {
		return m_currency;
	}

	public void currency(String currency) {
		this.m_currency = currency;
	}

	public double realizedPNL() {
		return m_realizedPNL;
	}

	public void realizedPNL(double realizedPNL) {
		this.m_realizedPNL = realizedPNL;
	}

	public double yield() {
		return m_yield;
	}

	public void yield(double yield) {
		this.m_yield = yield;
	}

	public int yieldRedemptionDate() {
		return m_yieldRedemptionDate;
	}

	public void yieldRedemptionDate(int yieldRedemptionDate) {
		this.m_yieldRedemptionDate = yieldRedemptionDate;
	}
    
}
