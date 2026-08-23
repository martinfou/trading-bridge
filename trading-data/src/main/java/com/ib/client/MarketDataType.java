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

public class MarketDataType {
    // constants - market data types
    public static final int REALTIME   = 1;
    public static final int FROZEN     = 2;
    public static final int DELAYED    = 3;
    public static final int DELAYED_FROZEN = 4;

    private static final String REALTIME_STR = "Real-Time";
    private static final String FROZEN_STR = "Frozen";
    private static final String DELAYED_STR = "Delayed";
    private static final String DELAYED_FROZEN_STR = "Delayed-Frozen";
    private static final String UNKNOWN_STR = "Unknown";

    public static String getField( int marketDataType) {
        switch( marketDataType) {
            case REALTIME:                    return REALTIME_STR;
            case FROZEN:                      return FROZEN_STR;
            case DELAYED:                     return DELAYED_STR;
            case DELAYED_FROZEN:              return DELAYED_FROZEN_STR;

            default:                          return UNKNOWN_STR;
        }
    }

    public static int getField( String marketDataTypeStr) {
        switch( marketDataTypeStr) {
            case REALTIME_STR:                return REALTIME;
            case FROZEN_STR:                  return FROZEN;
            case DELAYED_STR:                 return DELAYED;
            case DELAYED_FROZEN_STR:          return DELAYED_FROZEN;

            default:                          return Integer.MAX_VALUE;
        }
    }

    public static String[] getFields(){
    	int totalFields = MarketDataType.class.getFields().length;
    	String [] fields = new String[totalFields];
    	for (int i = 0; i < totalFields; i++){
    		fields[i] = MarketDataType.getField(i + 1);
    	}
    	return fields;
    }
}
