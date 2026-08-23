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

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;


public abstract class OrderCondition {

    protected static final String SPACE = " ";
    protected static final String EMPTY = "";
    private static final String AND = SPACE + "and";
    private static final String OR = SPACE + "or";

    private OrderConditionType m_type;
    private boolean m_isConjunctionConnection;

    public void readFrom(ObjectInput in) throws IOException {
        conjunctionConnection(in.readUTF().compareToIgnoreCase("a") == 0);
    }

    public void writeTo(ObjectOutput out) throws IOException {
        out.writeUTF(conjunctionConnection() ? "a" : "o");
    }


    @Override
    public String toString() {
        return conjunctionConnection() ? AND : OR;
    }

    public boolean conjunctionConnection() {
        return m_isConjunctionConnection;
    }

    public void conjunctionConnection(boolean isConjunctionConnection) {
        this.m_isConjunctionConnection = isConjunctionConnection;
    }

    public OrderConditionType type() {
        return m_type;
    }

    public static OrderCondition create(OrderConditionType type) {
        OrderCondition orderCondition;
        switch (type) {
            case Execution:
                orderCondition = new ExecutionCondition();
                break;

            case Margin:
                orderCondition = new MarginCondition();
                break;

            case PercentChange:
                orderCondition = new PercentChangeCondition();
                break;

            case Price:
                orderCondition = new PriceCondition();
                break;

            case Time:
                orderCondition = new TimeCondition();
                break;

            case Volume:
                orderCondition = new VolumeCondition();
                break;

            default:
                return null;
        }
        orderCondition.m_type = type;
        return orderCondition;
    }
    
    public boolean tryToParse(String conditionStr) {
        m_isConjunctionConnection = conditionStr.equals(AND);
        return m_isConjunctionConnection || conditionStr.equals(OR);
    }
}