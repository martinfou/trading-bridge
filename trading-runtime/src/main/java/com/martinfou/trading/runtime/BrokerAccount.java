package com.martinfou.trading.runtime;

/** Local broker account identity for multi-account prop deployments (Story 16.9 / PS-GR15). */
public record BrokerAccount(
    String id,
    String provider,
    String accountIdMasked,
    boolean credentialsConfigured
) {}
