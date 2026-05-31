package com.martinfou.trading.broker;

/** Snapshot of broker account balances. */
public record AccountState(double balance, double equity, String currency) {}
