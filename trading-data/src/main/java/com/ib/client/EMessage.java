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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class EMessage {
	ByteArrayOutputStream m_buf;
	
	public EMessage(byte[] buf, int len) {
		m_buf = new ByteArrayOutputStream();
		
		m_buf.write(buf, 0, len);
	}
	
	public EMessage(Builder buf) throws IOException {
		m_buf = new ByteArrayOutputStream();
		
			buf.writeTo(new DataOutputStream(m_buf));
	}
	
	public InputStream getStream() {
		return new ByteArrayInputStream(m_buf.toByteArray());
	}
	
	public byte[] getRawData() {		
		return m_buf.toByteArray();
	}

    public int getSize() {
        return m_buf.size();
    }
}
