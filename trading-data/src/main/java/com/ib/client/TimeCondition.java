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

public class TimeCondition extends OperatorCondition {
	
	public static final OrderConditionType conditionType = OrderConditionType.Time;

    private static final String HEADER = "time";

	protected TimeCondition() { }
	
	@Override
	public String toString() {
		return HEADER + super.toString();
	}

	private String m_time;

	public String time() {
		return m_time;
	}

	public void time(String m_time) {
		this.m_time = m_time;
	}

	@Override
	protected String valueToString() {
		return m_time;
	}

	@Override
	protected void valueFromString(String v) {
		m_time = v;
	}
	
    @Override public boolean tryToParse(String conditionStr) {
        if (!conditionStr.startsWith(HEADER))
            return false;
        conditionStr = conditionStr.replace(HEADER, EMPTY);
        return super.tryToParse(conditionStr);
    }
}