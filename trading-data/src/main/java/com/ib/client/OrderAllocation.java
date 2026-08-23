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

public class OrderAllocation {
    private String m_account;
    private Decimal m_position;
    private Decimal m_positionDesired;
    private Decimal m_positionAfter;
    private Decimal m_desiredAllocQty;
    private Decimal m_allowedAllocQty;
    private boolean m_isMonetary;

    public OrderAllocation() {
        m_account = "";
        m_position = Decimal.INVALID;
        m_positionDesired = Decimal.INVALID;
        m_positionAfter = Decimal.INVALID;
        m_desiredAllocQty = Decimal.INVALID;
        m_allowedAllocQty = Decimal.INVALID;
        m_isMonetary = false;
    }

    @Override
    public boolean equals(Object p_other) {
        if (this == p_other) {
            return true;
        }
        if (!(p_other instanceof OrderAllocation)) {
            return false;
        }
        OrderAllocation l_theOther = (OrderAllocation)p_other;
        return m_account.equals(l_theOther.m_account);
    }

    @Override
    public int hashCode() {
        return m_account == null ? 0 : m_account.hashCode();
    }

    public String account() {
        return m_account;
    }

    public void account(String account) {
        this.m_account = account;
    }

    public Decimal position() {
        return m_position;
    }

    public void position(Decimal position) {
        this.m_position = position;
    }

    public Decimal positionDesired() {
        return m_positionDesired;
    }

    public void positionDesired(Decimal positionDesired) {
        this.m_positionDesired = positionDesired;
    }

    public Decimal positionAfter() {
        return m_positionAfter;
    }

    public void positionAfter(Decimal positionAfter) {
        this.m_positionAfter = positionAfter;
    }

    public Decimal desiredAllocQty() {
        return m_desiredAllocQty;
    }

    public void desiredAllocQty(Decimal desiredAllocQty) {
        this.m_desiredAllocQty = desiredAllocQty;
    }

    public Decimal allowedAllocQty() {
        return m_allowedAllocQty;
    }

    public void allowedAllocQty(Decimal allowedAllocQty) {
        this.m_allowedAllocQty = allowedAllocQty;
    }

    public boolean isMonetary() {
        return m_isMonetary;
    }

    public void isMonetary(boolean isMonetary) {
        this.m_isMonetary = isMonetary;
    }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("account=").append(m_account);
        sb.append("; position=").append(m_position);
        sb.append("; positionDesired=").append(m_positionDesired);
        sb.append("; positionAfter=").append(m_positionAfter);
        sb.append("; desiredAllocQty=").append(m_desiredAllocQty);
        sb.append("; allowedAllocQty=").append(m_allowedAllocQty);
        sb.append("; isMonetary=").append(m_isMonetary);
        return sb.toString();
    }

    public String toShortString() {
        StringBuilder sb = new StringBuilder();
        sb.append("acc=").append(m_account);
        sb.append("; pos=").append(m_position);
        sb.append("; posD=").append(m_positionDesired);
        sb.append("; posA=").append(m_positionAfter);
        sb.append("; desAQty=").append(m_desiredAllocQty);
        sb.append("; allAQty=").append(m_allowedAllocQty);
        sb.append("; isMon=").append(m_isMonetary);
        return sb.toString();
    }
}
