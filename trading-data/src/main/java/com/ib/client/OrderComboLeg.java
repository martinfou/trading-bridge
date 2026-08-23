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


public class OrderComboLeg {
    private double m_price; // price per leg

    public double price()       { return m_price; }
    public void price(double v) { m_price = v; }
    
    public OrderComboLeg() {
        m_price = Double.MAX_VALUE;
    }

    public OrderComboLeg(double p_price) {
        m_price = p_price;
    }

    @Override
    public boolean equals(Object p_other) {
        if (this == p_other) {
            return true;
        }
        if (!(p_other instanceof OrderComboLeg)) {
            return false;
        }

        OrderComboLeg l_theOther = (OrderComboLeg)p_other;

        return m_price == l_theOther.m_price;
    }

    @Override
    public int hashCode() {
        long temp = Double.doubleToLongBits(m_price);
        return (int) (temp ^ (temp >>> 32));
    }
}
