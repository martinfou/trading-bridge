package com.martinfou.trading.data.oanda;

public record OandaInstrument(
    String name,
    String type,
    int displayPrecision,
    int pipLocation,
    double marginRate
) {}
