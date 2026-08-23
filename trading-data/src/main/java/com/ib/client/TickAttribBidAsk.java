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

public class TickAttribBidAsk {
	private boolean m_bidPastLow = false;
	private boolean m_askPastHigh = false;
	
	public boolean bidPastLow() {
		return m_bidPastLow;
	}
	public boolean askPastHigh() {
		return m_askPastHigh;
	}
	public void bidPastLow(boolean bidPastLow) {
		this.m_bidPastLow = bidPastLow;
	}
	public void askPastHigh(boolean askPastHigh) {
		this.m_askPastHigh = askPastHigh;
	}
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append(m_bidPastLow ? "bidPastLow " : "");
		sb.append(m_askPastHigh ? "askPastHigh " : "");
		return sb.toString();
	}
}
