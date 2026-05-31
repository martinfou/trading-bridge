package com.martinfou.trading.parser.sq;

/** Thrown when StrategyQuant strategy XML cannot be parsed. */
public class SqXmlParseException extends RuntimeException {

    public SqXmlParseException(String message) {
        super(message);
    }

    public SqXmlParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
