package com.martinfou.trading.data;

import com.martinfou.trading.core.exceptions.BrokerException;

/**
 * Specific exception class for OANDA API and data client errors.
 */
public class OandaApiException extends BrokerException {
    public OandaApiException(String message) {
        super(message);
    }

    public OandaApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
