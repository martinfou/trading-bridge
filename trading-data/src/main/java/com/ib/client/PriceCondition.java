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
import java.util.Arrays;
import java.util.Optional;

public class PriceCondition extends ContractCondition {
	
	public static final OrderConditionType conditionType = OrderConditionType.Price;
	
	protected PriceCondition() { }
	
	private double m_price;
	private int m_triggerMethod;
	private static String[] mthdNames = new String[] { "default", "double bid/ask", "last", "double last", "bid/ask", "", "", "last of bid/ask", "mid-point" };

	@Override
	public String toString() {
		return toString(null);
	}

	public double price() {
		return m_price;
	}

	public void price(double m_price) {
		this.m_price = m_price;
	}

	@Override
	public String toString(ContractLookuper lookuper) {
		return strTriggerMethod() + " " + super.toString(lookuper);
	}

	public int triggerMethod() {
		return m_triggerMethod;
	}
	
	String strTriggerMethod() {		
		return mthdNames[triggerMethod()];
	}

	public void triggerMethod(int m_triggerMethod) {
		this.m_triggerMethod = m_triggerMethod;
	}

	@Override
	protected String valueToString() {
		return Util.DoubleMaxString(m_price);
	}

	@Override
	protected void valueFromString(String v) {
		m_price = Double.parseDouble(v);
	}

	@Override
	public void readFrom(ObjectInput in) throws IOException {
		super.readFrom(in);
		
		m_triggerMethod = in.readInt();
	}

	@Override
	public void writeTo(ObjectOutput out) throws IOException {
		super.writeTo(out);
		out.writeInt(m_triggerMethod);
	}
	
    public static int triggerMethodFromString(String name) {
        return Arrays.asList(mthdNames).indexOf(name);
    }

    @Override public boolean tryToParse(final String conditionStr) {
        Optional<String> triggerMethod = Arrays.stream(mthdNames).filter(name -> !name.isEmpty()).filter(name -> conditionStr.startsWith(name)).sorted((name1, name2) -> name2.length() - name1.length()).findFirst();
        if (!triggerMethod.isPresent()) {
            return false;
        }
        try {
            m_triggerMethod = triggerMethodFromString(triggerMethod.get());
            return super.tryToParse(conditionStr.substring(conditionStr.indexOf(triggerMethod.get()) + triggerMethod.get().length() + 1));
        }
        catch (Exception ex) {
            return false;
        }
    }
}