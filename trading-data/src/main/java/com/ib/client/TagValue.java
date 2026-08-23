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

public final class TagValue {
	public String m_tag;
	public String m_value;

	public TagValue() {
	}

	public TagValue(String p_tag, String p_value) {
		m_tag = p_tag;
		m_value = p_value;
	}

	@Override
    public boolean equals(Object p_other) {
		if (this == p_other) {
			return true;
		}
        if(!(p_other instanceof TagValue)) {
			return false;
		}
        TagValue l_theOther = (TagValue)p_other;

		return Util.StringCompare(m_tag, l_theOther.m_tag) == 0
				&& Util.StringCompare(m_value, l_theOther.m_value) == 0;
	}

	@Override
	public int hashCode() {
		int result = (m_tag == null || "".equals(m_tag)) ? 0 : m_tag.hashCode();
		result = result * 31 + ((m_value == null || "".equals(m_value)) ? 0 : m_value.hashCode());
		return result;
	}
}
