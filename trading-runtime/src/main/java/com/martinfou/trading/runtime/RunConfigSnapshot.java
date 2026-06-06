package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.BacktestExecutionCost;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.core.LotSizing;

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
    String barsSourcePath,
    Double capital,
    Double quantity,
    Double commissionPerTrade,
    Double slippagePct,
    String executionLabel,
    String brokerAccountId,
    String dataTimeframe,
    String strategyTimeframe,
    Double maxDailyDrawdownPct,
    Double dailyLossLimitPct,
    Double weeklyLossLimitPct
) {

    public RunConfigSnapshot(
        String strategyId,
        String symbol,
        String mode,
        String barsSourceType,
        Integer barsSourceCount,
        String barsSourceYear,
        String barsSourcePath,
        Double capital,
        Double quantity,
        Double commissionPerTrade,
        Double slippagePct,
        String executionLabel,
        String brokerAccountId
    ) {
        this(strategyId, symbol, mode, barsSourceType, barsSourceCount, barsSourceYear, barsSourcePath,
            capital, quantity, commissionPerTrade, slippagePct, executionLabel, brokerAccountId, null, null, null, null, null);
    }

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
        this(strategyId, symbol, mode, barsSourceType, barsSourceCount, barsSourceYear, null,
            capital, null, commissionPerTrade, slippagePct, executionLabel, null, null, null, null, null, null);
    }

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
        String executionLabel,
        String brokerAccountId
    ) {
        this(strategyId, symbol, mode, barsSourceType, barsSourceCount, barsSourceYear, null,
            capital, null, commissionPerTrade, slippagePct, executionLabel, brokerAccountId, null, null, null, null, null);
    }

    public RunConfigSnapshot {
        if (commissionPerTrade == null) {
            commissionPerTrade = 0.0;
        }
        if (slippagePct == null) {
            slippagePct = 0.0;
        }
        if (dataTimeframe == null) {
            dataTimeframe = "H1";
        }
        if (strategyTimeframe == null) {
            strategyTimeframe = "H1";
        }
        if (maxDailyDrawdownPct == null) {
            maxDailyDrawdownPct = 5.0;
        }
        if (dailyLossLimitPct == null) {
            dailyLossLimitPct = 5.0;
        }
        if (weeklyLossLimitPct == null) {
            weeklyLossLimitPct = 10.0;
        }
    }

    public double resolvedCapital() {
        return LotSizing.resolveCapital(capital);
    }

    public double resolvedQuantityUnits() {
        return LotSizing.resolveQuantityUnits(quantity);
    }

    public double resolvedLotSize() {
        return LotSizing.unitsToLots(resolvedQuantityUnits());
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
        Object path = map.get("barsSourcePath");
        String barsPath = path != null ? String.valueOf(path) : null;
        Object capital = map.get("capital");
        Double capitalValue = capital instanceof Number n ? n.doubleValue() : null;
        Object quantityValue = map.get("quantity");
        Double quantity = quantityValue instanceof Number n ? n.doubleValue() : null;
        Object commission = map.get("commissionPerTrade");
        Double commissionValue = commission instanceof Number n ? n.doubleValue() : null;
        Object slippage = map.get("slippagePct");
        Double slippageValue = slippage instanceof Number n ? n.doubleValue() : null;
        String dataTf = map.get("dataTimeframe") != null ? String.valueOf(map.get("dataTimeframe")) : null;
        String stratTf = map.get("strategyTimeframe") != null ? String.valueOf(map.get("strategyTimeframe")) : null;
        Object maxDd = map.get("maxDailyDrawdownPct");
        Double maxDdVal = maxDd instanceof Number n ? n.doubleValue() : null;
        Object dailyLoss = map.get("dailyLossLimitPct");
        Double dailyLossVal = dailyLoss instanceof Number n ? n.doubleValue() : null;
        Object weeklyLoss = map.get("weeklyLossLimitPct");
        Double weeklyLossVal = weeklyLoss instanceof Number n ? n.doubleValue() : null;
        return new RunConfigSnapshot(
            String.valueOf(map.getOrDefault("strategyId", record.strategyId())),
            String.valueOf(map.getOrDefault("symbol", record.symbol())),
            String.valueOf(map.getOrDefault("mode", record.mode().name())),
            map.get("barsSourceType") != null ? String.valueOf(map.get("barsSourceType")) : null,
            barsCount,
            barsYear,
            barsPath,
            capitalValue,
            quantity,
            commissionValue,
            slippageValue,
            map.get("executionLabel") != null ? String.valueOf(map.get("executionLabel")) : null,
            map.get("brokerAccountId") != null ? String.valueOf(map.get("brokerAccountId")) : null,
            dataTf,
            stratTf,
            maxDdVal,
            dailyLossVal,
            weeklyLossVal);
    }

    public static RunConfigSnapshot fromRequest(RunManager.StartRunRequest request, String resolvedSymbol) {
        BarSourceResolver.BarsSource source = request.barsSource();
        Double quantityUnits = request.lotSize() != null
            ? LotSizing.lotsToUnits(request.lotSize())
            : null;
        return new RunConfigSnapshot(
            request.strategyId(),
            resolvedSymbol,
            request.mode().toUpperCase(),
            source != null ? source.type() : null,
            source != null ? source.count() : null,
            source != null && source.yearSpec() != null ? source.yearSpec() : null,
            source != null ? source.path() : null,
            request.capital(),
            quantityUnits,
            request.commissionPerTrade(),
            request.slippagePct(),
            request.executionLabel(),
            request.brokerAccountId(),
            request.dataTimeframe(),
            request.strategyTimeframe(),
            request.maxDailyDrawdownPct(),
            request.dailyLossLimitPct(),
            request.weeklyLossLimitPct());
    }

    public RunConfigSnapshot withBrokerAccountId(String accountId) {
        return new RunConfigSnapshot(
            strategyId, symbol, mode, barsSourceType, barsSourceCount, barsSourceYear, barsSourcePath,
            capital, quantity, commissionPerTrade, slippagePct, executionLabel, accountId, dataTimeframe, strategyTimeframe,
            maxDailyDrawdownPct, dailyLossLimitPct, weeklyLossLimitPct);
    }

    public RunConfigSnapshot withCapital(Double newCapital) {
        return new RunConfigSnapshot(
            strategyId, symbol, mode, barsSourceType, barsSourceCount, barsSourceYear, barsSourcePath,
            newCapital, quantity, commissionPerTrade, slippagePct, executionLabel, brokerAccountId, dataTimeframe, strategyTimeframe,
            maxDailyDrawdownPct, dailyLossLimitPct, weeklyLossLimitPct);
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
        if (barsSourcePath != null && !barsSourcePath.isBlank()) {
            map.put("barsSourcePath", barsSourcePath);
        }
        if (capital != null) {
            map.put("capital", capital);
        }
        map.put("quantity", resolvedQuantityUnits());
        map.put("lotSize", resolvedLotSize());
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
        if (dataTimeframe != null) {
            map.put("dataTimeframe", dataTimeframe);
        }
        if (strategyTimeframe != null) {
            map.put("strategyTimeframe", strategyTimeframe);
        }
        if (maxDailyDrawdownPct != null) {
            map.put("maxDailyDrawdownPct", maxDailyDrawdownPct);
        }
        if (dailyLossLimitPct != null) {
            map.put("dailyLossLimitPct", dailyLossLimitPct);
        }
        if (weeklyLossLimitPct != null) {
            map.put("weeklyLossLimitPct", weeklyLossLimitPct);
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
