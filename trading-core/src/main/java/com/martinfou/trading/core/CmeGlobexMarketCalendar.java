package com.martinfou.trading.core;

import java.time.*;

/**
 * Market calendar and session classifier for CME Globex Electronic Futures (MES, MNQ, M2K, EMD, ES, NQ).
 *
 * <p>CME Globex Schedule (all times in US Eastern Time - {@code America/New_York}):</p>
 * <ul>
 *   <li>Sunday: Opens at 18:00 ET</li>
 *   <li>Monday–Thursday: Continuous with daily pause 16:15–16:30 ET and maintenance halt 17:00–18:00 ET</li>
 *   <li>Friday: Closes at 17:00 ET for the weekend</li>
 *   <li>Saturday: Closed all day</li>
 * </ul>
 *
 * <p>Session Definitions:</p>
 * <ul>
 *   <li><b>RTH (Regular Trading Hours):</b> 09:30 to 16:00 ET (Monday–Friday)</li>
 *   <li><b>ETH (Extended Trading Hours):</b> 18:00 to 09:30 ET and 16:00 to 17:00 ET</li>
 * </ul>
 */
public final class CmeGlobexMarketCalendar {

    public static final ZoneId EASTERN_ZONE = ZoneId.of("America/New_York");

    public enum SessionType {
        RTH,
        ETH,
        CLOSED
    }

    private CmeGlobexMarketCalendar() {}

    /**
     * Determines whether CME Globex is open for trading at the given UTC Instant.
     */
    public static boolean isTradingTime(Instant instant) {
        if (instant == null) return false;
        ZonedDateTime et = instant.atZone(EASTERN_ZONE);
        DayOfWeek dow = et.getDayOfWeek();
        int hour = et.getHour();
        int minute = et.getMinute();
        int timeInMinutes = hour * 60 + minute;

        if (dow == DayOfWeek.SATURDAY) {
            return false;
        }

        if (dow == DayOfWeek.SUNDAY) {
            // Opens at 18:00 ET (1080 minutes)
            return timeInMinutes >= 18 * 60;
        }

        if (dow == DayOfWeek.FRIDAY) {
            // Closes at 17:00 ET (1020 minutes)
            if (timeInMinutes >= 17 * 60) {
                return false;
            }
            // Daily pause 16:15 - 16:30 ET
            return !(timeInMinutes >= 16 * 60 + 15 && timeInMinutes < 16 * 60 + 30);
        }

        // Monday through Thursday
        // Daily maintenance halt 17:00 - 18:00 ET
        if (timeInMinutes >= 17 * 60 && timeInMinutes < 18 * 60) {
            return false;
        }

        // Daily pause 16:15 - 16:30 ET
        if (timeInMinutes >= 16 * 60 + 15 && timeInMinutes < 16 * 60 + 30) {
            return false;
        }

        return true;
    }

    /**
     * Checks if a historical bar falls within valid CME Globex trading hours.
     */
    public static boolean isTradingBar(Bar bar) {
        if (bar == null || bar.timestamp() == null) return false;
        return isTradingTime(bar.timestamp());
    }

    /**
     * Returns the session classification (RTH, ETH, CLOSED) for a given timestamp.
     */
    public static SessionType classifySession(Instant instant) {
        if (!isTradingTime(instant)) {
            return SessionType.CLOSED;
        }
        ZonedDateTime et = instant.atZone(EASTERN_ZONE);
        int timeInMinutes = et.getHour() * 60 + et.getMinute();
        DayOfWeek dow = et.getDayOfWeek();

        // RTH is Monday - Friday 09:30 to 16:00 ET
        if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
            if (timeInMinutes >= 9 * 60 + 30 && timeInMinutes < 16 * 60) {
                return SessionType.RTH;
            }
        }

        return SessionType.ETH;
    }

    public static boolean isRth(Instant instant) {
        return classifySession(instant) == SessionType.RTH;
    }

    public static boolean isEth(Instant instant) {
        return classifySession(instant) == SessionType.ETH;
    }
}
