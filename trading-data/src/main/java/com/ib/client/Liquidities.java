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

public enum Liquidities {
    None,
    Added("Added Liquidity"),
    Removed("Removed Liquidity"),
    RoudedOut("Liquidity Routed Out");
    
    private String m_text;
    
    Liquidities(String text) {
        m_text = text;
    }
    
    Liquidities() {
        m_text = "None";
    }
    
    @Override
    public String toString() {
        return m_text;
    }
    
    public static Liquidities fromInt(int n) {
        if (n < 0 || n > Liquidities.values().length) {
            return Liquidities.None;
        }
        
        return Liquidities.values()[n];
    }
    
    public static int toInt(Liquidities l) {
        return l.ordinal();
    }
}
