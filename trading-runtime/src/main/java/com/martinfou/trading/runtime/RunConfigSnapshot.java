package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.BacktestExecutionCost;
import com.martinfou.trading.backtest.RunMode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable strategy-run configuration captured at launch (ADR-13-11).
 */
public record RunConfigSnapshot(
    String strategyId,
    String symbol,
    String mode,
    String barsSourceType,
    Integer barsSourceCount,
    String barsSourceYear,
    Double capital,
    Double commissionPerTrade,
    Double slippagePct,
    String executionLabel,
    String brokerAccountId
) {

    public RunConfigSnapshot(
        String strategyId,
        String symbol,
        String mode,
        String barsSourceType,
        Integer barsSourceCount,
        String barsSourceYear,
        Double capital,
        Double commissionPerTrade,
        Double slippagePct,
        String executionLabel
    ) {
        this(strategyId, symbol, mode, barsSourceType, barsSourceCount, barsSourceYear,
            capital, commissionPerTrade, slippagePct, executionLabel, null);
    }

    public RunConfigSnapshot {
        if (commissionPerTrade == null) {
            commissionPerTrade = 0.0;
        }
        if (slippagePct == null) {
            slippagePct = 0.0;
        }
    }

    public BacktestExecutionCost executionCost() {
        return BacktestExecutionCost.ofCommissionAndSlippage(commissionPerTrade, slippagePct);
    }

    public ExecutionLabel resolvedExecutionLabel() {
        RunMode runMode = RunMode.valueOf(mode.toUpperCase());
        if (executionLabel != null && !executionLabel.isBlank()) {
            return ExecutionLabel.parse(executionLabel);
        }
        return ExecutionLabel.forRunMode(runMode);
    }

    public static RunConfigSnapshot fromRecord(RunRecord record) {
        Map<String, Object> map = record.configSnapshot();
        Object count = map.get("barsSourceCount");
        Integer barsCount = count instanceof Number n ? n.intValue() : null;
        Object year = map.get("barsSourceYear");
        String barsYear = year != null ? String.valueOf(year) : null;
        Object capital = map.get("capital");
        Double capitalValue = capital instanceof Number n ? n.doubleValue() : null;
        Object commission = map.get("commissionPerTrade");
        Double commissionValue = commission instanceof Number n ? n.doubleValue() : null;
        Object slippage = map.get("slippagePct");
        Double slippageValue = slippage instanceof Number n ? n.doubleValue() : null;
        return new RunConfigSnapshot(
            String.valueOf(map.getOrDefault("strategyId", record.strategyId())),
            String.valueOf(map.getOrDefault("symbol", record.symbol())),
            String.valueOf(map.getOrDefault("mode", record.mode().name())),
            map.get("barsSourceType") != null ? String.valueOf(map.get("barsSourceType")) : null,
            barsCount,
            barsYear,
            capitalValue,
            commissionValue,
            slippageValue,
            map.get("executionLabel") != null ? String.valueOf(map.get("executionLabel")) : null,
            map.get("brokerAccountId") != null ? String.valueOf(map.get("brokerAccountId")) : null);
    }

    public static RunConfigSnapshot fromRequest(RunManager.StartRunRequest request, String resolvedSymbol) {
        BarSourceResolver.BarsSource source = request.barsSource();
        return new RunConfigSnapshot(
            request.strategyId(),
            resolvedSymbol,
            request.mode().toUpperCase(),
            source != null ? source.type() : null,
            source != null ? source.count() : null,
            source != null && source.year() != null ? String.valueOf(source.year()) : null,
            request.capital(),
            request.commissionPerTrade(),
            request.slippagePct(),
            request.executionLabel(),
            request.brokerAccountId());
    }

    public RunConfigSnapshot withBrokerAccountId(String accountId) {
        return new RunConfigSnapshot(
            strategyId, symbol, mode, barsSourceType, barsSourceCount, barsSourceYear,
            capital, commissionPerTrade, slippagePct, executionLabel, accountId);
    }

    public String resolvedBrokerAccountId() {
        return BrokerAccountRegistry.resolveId(brokerAccountId);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("strategyId", strategyId);
        map.put("symbol", symbol);
        map.put("mode", mode);
        map.put("barsSourceType", barsSourceType);
        if (barsSourceCount != null) {
            map.put("barsSourceCount", barsSourceCount);
        }
        if (barsSourceYear != null) {
            map.put("barsSourceYear", barsSourceYear);
        }
        if (capital != null) {
            map.put("capital", capital);
        }
        if (commissionPerTrade != null && commissionPerTrade != 0.0) {
            map.put("commissionPerTrade", commissionPerTrade);
        }
        if (slippagePct != null && slippagePct != 0.0) {
            map.put("slippagePct", slippagePct);
        }
        if (executionLabel != null && !executionLabel.isBlank()) {
            map.put("executionLabel", executionLabel);
        }
        if (brokerAccountId != null && !brokerAccountId.isBlank()) {
            map.put("brokerAccountId", brokerAccountId);
        }
        map.put("resolvedExecutionLabel", resolvedExecutionLabel().name());
        return Map.copyOf(map);
    }

    /** SHA-256 hex digest of the canonical snapshot map string. */
    public String hash() {
        String canonical = toMap().toString();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
