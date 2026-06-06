package com.martinfou.trading.intelligence.llm;

/** LLM call failure. */
public final class LlmException extends Exception {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
