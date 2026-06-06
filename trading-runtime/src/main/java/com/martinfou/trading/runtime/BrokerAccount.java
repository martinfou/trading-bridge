package com.martinfou.trading.runtime;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Local broker account identity for multi-account prop deployments (Story 16.9 / PS-GR15). */
public record BrokerAccount(
    @JsonProperty("id") String id,
    @JsonProperty("provider") String provider,
    @JsonProperty("maskedAccountId") String accountIdMasked,
    @JsonProperty("configured") boolean credentialsConfigured,
    @JsonProperty("host") String host,
    @JsonProperty("port") Integer port
) {}
