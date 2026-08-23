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

public class TickAttrib {
	private boolean m_canAutoExecute = false;
	private boolean m_pastLimit = false;
	private boolean m_preOpen = false;
	
	public boolean canAutoExecute() {
		return m_canAutoExecute;
	}
	public boolean pastLimit() {
		return m_pastLimit;
	}
	public boolean preOpen() {
		return m_preOpen;
	}
	public void canAutoExecute(boolean canAutoExecute) {
		this.m_canAutoExecute = canAutoExecute;
	}
	public void pastLimit(boolean pastLimit) {
		this.m_pastLimit = pastLimit;
	}
	public void preOpen(boolean preOpen) {
		this.m_preOpen = preOpen;
	}
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append(m_canAutoExecute ? "canAutoExecute " : "");
		sb.append(m_pastLimit ? "pastLimit " : "");
		sb.append(m_preOpen ? "preOpen " : "");
		return sb.toString();
	}
}
