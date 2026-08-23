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

public class DeltaNeutralContract {
	private int    m_conid;
	private double m_delta;
	private double m_price;

	// Get
	public int    conid()  { return m_conid; }
	public double delta()  { return m_delta; }
	public double price()  { return m_price; }

    // Set
	public void conid(int conid)     { m_conid = conid; }
    public void delta(double delta)  { m_delta = delta; }
    public void price(double price)  { m_price = price; }

    public DeltaNeutralContract() {
	    m_conid = 0;
	    m_delta = 0;
	    m_price = 0;
	}

	public DeltaNeutralContract(int conid, double delta, double price) {
		m_conid = conid;
		m_delta = delta;
		m_price = price;
	}

    @Override
    public boolean equals(Object p_other) {
        if (this == p_other) {
            return true;
        }

        if (p_other == null || !(p_other instanceof DeltaNeutralContract)) {
            return false;
        }

        DeltaNeutralContract l_theOther = (DeltaNeutralContract)p_other;

        if (m_conid != l_theOther.m_conid) {
            return false;
        }
        if (m_delta != l_theOther.m_delta) {
            return false;
        }
        if (m_price != l_theOther.m_price) {
            return false;
        }

        return true;
    }

    @Override
    public int hashCode() {
        int result;
        long temp;
        result = m_conid;
        temp = Double.doubleToLongBits(m_delta);
        result = 31 * result + (int) (temp ^ (temp >>> 32));
        temp = Double.doubleToLongBits(m_price);
        result = 31 * result + (int) (temp ^ (temp >>> 32));
        return result;
    }
}
