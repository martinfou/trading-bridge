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

public class TickAttribLast {
	private boolean m_pastLimit = false; // aka halted
	private boolean m_unreported = false;
	
	public boolean pastLimit() {
		return m_pastLimit;
	}
	public boolean unreported() {
		return m_unreported;
	}
	public void pastLimit(boolean pastLimit) {
		this.m_pastLimit = pastLimit;
	}
	public void unreported(boolean unreported) {
		this.m_unreported = unreported;
	}
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append(m_pastLimit ? "pastLimit " : "");
		sb.append(m_unreported ? "unreported " : "");
		return sb.toString();
	}
}
