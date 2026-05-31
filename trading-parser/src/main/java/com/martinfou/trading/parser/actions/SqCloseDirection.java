package com.martinfou.trading.parser.actions;

/** Direction filter for {@code CloseAllPositions} (story 2-8). */
public enum SqCloseDirection {
    LONG(1),
    SHORT(-1),
    ANY(0);

    private final int sqCode;

    SqCloseDirection(int sqCode) {
        this.sqCode = sqCode;
    }

    public static SqCloseDirection fromSqCode(int code) {
        return switch (code) {
            case 1 -> LONG;
            case -1 -> SHORT;
            default -> ANY;
        };
    }

    public int sqCode() {
        return sqCode;
    }
}
