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

public class SoftDollarTier {

	private String m_name, m_value, m_displayName;
	
	public SoftDollarTier(String name, String val, String displayName) {
		name(name);
		value(val);	
		
		m_displayName = displayName;
	}
	
	public String value() {
		return m_value;
	}
	
	private void value(String value) {
		this.m_value = value;
	}

	public String name() {
		return m_name;
	}

	private void name(String name) {
		this.m_name = name;
	}	

	public String displayName() {
		return m_displayName;
	}

	private void displayName(String displayName) {
		this.m_displayName = displayName;
	}	
	
	@Override public int hashCode() {
		final int prime = 31;
		int result = 1;
		
		result = prime * result + ((m_name == null) ? 0 : m_name.hashCode());
		result = prime * result + ((m_value == null) ? 0 : m_value.hashCode());
		
		return result;
	}

	@Override public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		
		if (obj == null) {
			return false;
		}
		
		if (!(obj instanceof SoftDollarTier)) {
			return false;
		}
		
		SoftDollarTier other = (SoftDollarTier) obj;
		
		if (m_name == null) {
			if (other.m_name != null) {
				return false;
			}
		} else if (Util.StringCompare(m_name, other.m_name) != 0) {
			return false;
		}
		
		if (m_value == null) {
			if (other.m_value != null) {
				return false;
			}
		} else if (Util.StringCompare(m_value, other.m_value) != 0) {
			return false;
		}
		
		return true;
	}

	@Override public String toString() {
		return m_displayName;
	}

}
