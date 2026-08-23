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

public class HistogramEntry implements Comparable<HistogramEntry> {

    private double price;
    private Decimal size;

    public double price() {
		return price;
	}

	public void price(double price) {
		this.price = price;
	}

	public Decimal size() {
		return size;
	}

	public void size(Decimal size) {
		this.size = size;
	}

	public HistogramEntry(double price, Decimal size) {
        this.price = price;
        this.size = size;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || !(o instanceof HistogramEntry)) return false;
        HistogramEntry he = (HistogramEntry) o;
        return Double.compare(price, he.price) == 0 && Decimal.compare(size, he.size) == 0;
    }

    @Override
    public int hashCode() {
        int result;
        long tempPrice = Double.doubleToLongBits(price);
        result = (int) (tempPrice ^ (tempPrice >>> 32));
        result = 31 * result + size.hashCode();
        return result;
    }

    @Override
    public int compareTo(HistogramEntry he) {
        final int d = Double.compare(price, he.price);
        if (d != 0) {
            return d;
        }
        return Decimal.compare(size, he.size);
    }

    @Override
    public String toString() {
        return "HistogramEntry{" +
                "price=" + price +
                ", size=" + size +
                '}';
    }
}
