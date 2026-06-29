package com.martinfou.trading.runtime;

/** Cause of a {@link RunRecord} lifecycle transition. */
public enum RunTransition {
    REGISTER,
    START,
    STOP,
    PAUSE,
    RESUME,
    COMPLETE,
    FAIL,
    ARCHIVE,
    RETIRE
}
