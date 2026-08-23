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

import com.ib.client.EClientErrors.CodeMsgPair;

class EClientException extends IOException {

    private static final long serialVersionUID = 1L;
    private final CodeMsgPair m_error; 
    private final String m_text;
    
    public CodeMsgPair error() { return m_error; }
    public String text()       { return m_text; }
    
    EClientException(CodeMsgPair err, String text) {
        m_error = err;
        m_text = text;
    }
}
