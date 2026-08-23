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

public class VolumeCondition extends ContractCondition {
	
	public static final OrderConditionType conditionType = OrderConditionType.Volume;
	
	protected VolumeCondition() { }
	
    @Override
	public String toString() {
		return toString(null);
	}

	@Override
	public String toString(ContractLookuper lookuper) {
		return super.toString(lookuper);
	}

	private int m_volume;

	public int volume() {
		return m_volume;
	}

	public void volume(int m_volume) {
		this.m_volume = m_volume;
	}

	@Override
	protected String valueToString() {
		return Util.IntMaxString(m_volume);
	}

	@Override
	protected void valueFromString(String v) {
		m_volume = Integer.parseInt(v);
	}
	
}