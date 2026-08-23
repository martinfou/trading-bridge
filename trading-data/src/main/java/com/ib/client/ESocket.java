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

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;

public class ESocket implements ETransport {

    protected DataOutputStream m_dos;   // the socket output stream

    @Override
    public void send(EMessage msg) throws IOException {
        byte[] buf = msg.getRawData();

        m_dos.write(buf, 0, buf.length);
    }

    ESocket(Socket s) throws IOException {
        m_dos = new DataOutputStream(s.getOutputStream());
    }

    // Sends String without length prefix (pre-V100 style)
    protected void send(String str) throws IOException {
        // Write string to data buffer
        try (Builder b = new Builder(1024)) {
            b.send(str);
            b.writeTo(m_dos);
        }
    }

    @Override
    public void close() throws IOException {
        if (m_dos != null) {
            m_dos.close();
        }
    }
}
