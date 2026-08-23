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

public class PriceIncrement {
	private double m_lowEdge;
	private double m_increment;
	
	// Get
	public double lowEdge() { return m_lowEdge; }
	public double increment() { return m_increment; }
	
	// Set
	public void lowEdge(double lowEdge) { m_lowEdge = lowEdge; }
	public void increment(double increment) { m_increment = increment; }
	
	public PriceIncrement() {
	}
	
	public PriceIncrement(double p_lowEdge, double p_increment) {
		m_lowEdge = p_lowEdge;
		m_increment = p_increment;
	}
}
