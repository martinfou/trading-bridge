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

public class MarginCondition extends OperatorCondition {
	
	public static final OrderConditionType conditionType = OrderConditionType.Margin;

    private static final String HEADER = "the margin cushion percent";
	
	protected MarginCondition() { }
	
	@Override
	public String toString() {		
		return HEADER + super.toString();
	}

	private int m_percent;

	public int percent() {
		return m_percent;
	}

	public void percent(int m_percent) {
		this.m_percent = m_percent;
	}

	@Override
	protected String valueToString() {
		return Util.IntMaxString(m_percent);
	}

	@Override
	protected void valueFromString(String v) {
		m_percent = Integer.parseInt(v);
	}
	
    @Override public boolean tryToParse(String conditionStr) {
        if (!conditionStr.startsWith(HEADER)) {
            return false;
        }
        conditionStr = conditionStr.replace(HEADER, EMPTY);
        return super.tryToParse(conditionStr);
    }
}