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

public class BitMask {

    private int m_mask = 0;
    
    public BitMask(int mask) {
    	m_mask = mask;
    }
    
    public int getMask() {
        return m_mask;
    }

    public void clear() {
        m_mask = 0;
    }

    public boolean get(int index) {
        if (index >= 32) {
            throw new IndexOutOfBoundsException();
        }
        
        return (m_mask & (1 << index)) != 0;
    }

    public boolean set(int index, boolean element) {
        boolean res = get(index);
        
        if (element) {
            m_mask |= 1 << index;
        } else {
            m_mask &= ~(1 << index);
        }
        
        return res;
    }

}
