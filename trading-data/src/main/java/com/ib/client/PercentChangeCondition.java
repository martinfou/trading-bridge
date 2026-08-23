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

public class PercentChangeCondition extends ContractCondition {

	public static final OrderConditionType conditionType = OrderConditionType.PercentChange;
	
	protected PercentChangeCondition() { }
	
	@Override
	public String toString(ContractLookuper lookuper) {
		return super.toString(lookuper);
	}

	@Override
	public String toString() {
		return toString(null);
	}

	private double m_changePercent = Double.MAX_VALUE;

	public double changePercent() {
		return m_changePercent;
	}

	public void changePercent(double m_changePercent) {
		this.m_changePercent = m_changePercent;
	}

	@Override
	protected String valueToString() {
		return Util.DoubleMaxString(m_changePercent);
	}

	@Override
	protected void valueFromString(String v) {
		m_changePercent = Double.parseDouble(v);
	} 
	
}