package com.martinfou.trading.backtest.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SqliteBacktestRunStoreTest {

    @TempDir
    Path tempDir;

    private Path dbFile;
    private SqliteBacktestRunStore store;

    @BeforeEach
    void setUp() {
        dbFile = tempDir.resolve("test_runs.db");
        store = new SqliteBacktestRunStore(dbFile);
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    @Test
    void testTableAndIndexInitialization() {
        // Since store is created in setUp, DDL is already run.
        // We verify we can interact with it without SQL errors.
        assertEquals(0, store.count(null));
    }

    @Test
    void testInsertAndGet() {
        Instant start = Instant.now().minus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        Instant end = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant created = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        BacktestRunDetails run = new BacktestRunDetails(
            "run_123",
            "SmaCrossover",
            "EUR_USD",
            start,
            end,
            "{\"fastPeriod\":20,\"slowPeriod\":50}",
            "abc555hash",
            10000.0,
            11500.0,
            1500.0,
            15.0,
            100,
            60,
            40,
            60.0,
            5.2,
            15.0,
            1.8,
            2.1,
            1.4,
            2.8,
            20.0,
            50.0,
            "[10000.0, 10500.0, 11500.0]",
            created
        );

        store.insert(run);

        Optional<BacktestRunDetails> retrievedOpt = store.get("run_123");
        assertTrue(retrievedOpt.isPresent());

        BacktestRunDetails retrieved = retrievedOpt.get();
        assertEquals(run.runId(), retrieved.runId());
        assertEquals(run.strategyId(), retrieved.strategyId());
        assertEquals(run.symbol(), retrieved.symbol());
        assertEquals(run.periodStart(), retrieved.periodStart());
        assertEquals(run.periodEnd(), retrieved.periodEnd());
        assertEquals(run.parameters(), retrieved.parameters());
        assertEquals(run.parameterHash(), retrieved.parameterHash());
        assertEquals(run.initialCapital(), retrieved.initialCapital(), 0.0001);
        assertEquals(run.finalEquity(), retrieved.finalEquity(), 0.0001);
        assertEquals(run.totalPnl(), retrieved.totalPnl(), 0.0001);
        assertEquals(run.totalReturnPct(), retrieved.totalReturnPct(), 0.0001);
        assertEquals(run.totalTrades(), retrieved.totalTrades());
        assertEquals(run.winningTrades(), retrieved.winningTrades());
        assertEquals(run.losingTrades(), retrieved.losingTrades());
        assertEquals(run.winRatePct(), retrieved.winRatePct(), 0.0001);
        assertEquals(run.maxDrawdownPct(), retrieved.maxDrawdownPct(), 0.0001);
        assertEquals(run.avgTradePnl(), retrieved.avgTradePnl(), 0.0001);
        assertEquals(run.sharpeRatio(), retrieved.sharpeRatio(), 0.0001);
        assertEquals(run.sortinoRatio(), retrieved.sortinoRatio(), 0.0001);
        assertEquals(run.profitFactor(), retrieved.profitFactor(), 0.0001);
        assertEquals(run.calmarRatio(), retrieved.calmarRatio(), 0.0001);
        assertEquals(run.totalCommission(), retrieved.totalCommission(), 0.0001);
        assertEquals(run.totalSlippage(), retrieved.totalSlippage(), 0.0001);
        assertEquals(run.equityCurve(), retrieved.equityCurve());
        assertEquals(run.createdAt(), retrieved.createdAt());
    }

    @Test
    void testGetNonExistent() {
        Optional<BacktestRunDetails> retrieved = store.get("non_existent_id");
        assertFalse(retrieved.isPresent());
    }

    @Test
    void testListAndCountFilters() {
        Instant base = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        BacktestRunDetails run1 = createSampleRun("run_1", "SmaCrossover", "EUR_USD", 1.5, 1.2, 1000.0, 5.0, base, 50, 60.0, 10000.0);
        BacktestRunDetails run2 = createSampleRun("run_2", "SmaCrossover", "GBP_USD", 2.2, 1.6, 2500.0, 3.5, base.plusSeconds(10), 100, 70.0, 20000.0);
        BacktestRunDetails run3 = createSampleRun("run_3", "RsiMeanReversion", "EUR_USD", 1.1, 1.1, 500.0, 8.0, base.plusSeconds(20), 10, 50.0, 5000.0);

        store.insert(run1);
        store.insert(run2);
        store.insert(run3);

        // 1. Count all
        assertEquals(3, store.count(null));

        // 2. Filter by Symbol
        BacktestQueryFilters filterSymbol = BacktestQueryFilters.builder().symbol("EUR_USD").build();
        assertEquals(2, store.count(filterSymbol));
        List<BacktestRunSummary> listSymbol = store.list(filterSymbol);
        assertEquals(2, listSymbol.size());
        assertTrue(listSymbol.stream().anyMatch(r -> r.runId().equals("run_1")));
        assertTrue(listSymbol.stream().anyMatch(r -> r.runId().equals("run_3")));

        // 3. Filter by StrategyId
        BacktestQueryFilters filterStrategy = BacktestQueryFilters.builder().strategyId("SmaCrossover").build();
        assertEquals(2, store.count(filterStrategy));

        // 4. Filter by Sharpe Ratio
        BacktestQueryFilters filterSharpe = BacktestQueryFilters.builder().minSharpe(1.4).build();
        assertEquals(2, store.count(filterSharpe)); // run_1 (1.5) and run_2 (2.2)

        // 5. Filter by Profit Factor
        BacktestQueryFilters filterPF = BacktestQueryFilters.builder().minProfitFactor(1.5).build();
        assertEquals(1, store.count(filterPF)); // run_2 (1.6)

        // 6. Test Sorting by Sharpe DESC
        BacktestQueryFilters sortSharpeDesc = BacktestQueryFilters.builder()
            .sortBy("sharpe")
            .sortOrder("DESC")
            .build();
        List<BacktestRunSummary> sortedSharpe = store.list(sortSharpeDesc);
        assertEquals(3, sortedSharpe.size());
        assertEquals("run_2", sortedSharpe.get(0).runId()); // 2.2
        assertEquals("run_1", sortedSharpe.get(1).runId()); // 1.5
        assertEquals("run_3", sortedSharpe.get(2).runId()); // 1.1

        // 7. Test Sorting by Drawdown ASC
        BacktestQueryFilters sortDrawdownAsc = BacktestQueryFilters.builder()
            .sortBy("drawdown")
            .sortOrder("ASC")
            .build();
        List<BacktestRunSummary> sortedDrawdown = store.list(sortDrawdownAsc);
        assertEquals(3, sortedDrawdown.size());
        assertEquals("run_2", sortedDrawdown.get(0).runId()); // 3.5
        assertEquals("run_1", sortedDrawdown.get(1).runId()); // 5.0
        assertEquals("run_3", sortedDrawdown.get(2).runId()); // 8.0

        // 8. Test Pagination (Limit/Offset)
        BacktestQueryFilters pagination = BacktestQueryFilters.builder()
            .limit(2)
            .offset(1)
            .sortBy("pnl")
            .sortOrder("DESC") // PnL: run_2 (2500), run_1 (1000), run_3 (500)
            .build();
        List<BacktestRunSummary> paginated = store.list(pagination);
        assertEquals(2, paginated.size());
        assertEquals("run_1", paginated.get(0).runId()); // 1000
        assertEquals("run_3", paginated.get(1).runId()); // 500

        // 9. Test Sorting by Strategy DESC
        BacktestQueryFilters sortStrategyDesc = BacktestQueryFilters.builder()
            .sortBy("strategy")
            .sortOrder("DESC")
            .build();
        List<BacktestRunSummary> sortedStrategy = store.list(sortStrategyDesc);
        assertEquals(3, sortedStrategy.size());
        // SmaCrossover (run_1, run_2) comes before RsiMeanReversion in DESC order.
        // Wait, "SmaCrossover" is lexicographically greater than "RsiMeanReversion", so "SmaCrossover" comes first in DESC.
        assertTrue(sortedStrategy.get(0).runId().equals("run_1") || sortedStrategy.get(0).runId().equals("run_2"));
        assertEquals("run_3", sortedStrategy.get(2).runId()); // "RsiMeanReversion" comes last

        // 10. Test Sorting by Symbol ASC
        BacktestQueryFilters sortSymbolAsc = BacktestQueryFilters.builder()
            .sortBy("symbol")
            .sortOrder("ASC")
            .build();
        List<BacktestRunSummary> sortedSymbol = store.list(sortSymbolAsc);
        assertEquals(3, sortedSymbol.size());
        // EUR_USD (run_1, run_3) comes before GBP_USD (run_2)
        assertEquals("run_2", sortedSymbol.get(2).runId());

        // 11. Test Sorting by Trades DESC
        BacktestQueryFilters sortTradesDesc = BacktestQueryFilters.builder()
            .sortBy("trades")
            .sortOrder("DESC")
            .build();
        List<BacktestRunSummary> sortedTrades = store.list(sortTradesDesc);
        assertEquals(3, sortedTrades.size());
        assertEquals("run_2", sortedTrades.get(0).runId()); // 100 trades
        assertEquals("run_1", sortedTrades.get(1).runId()); // 50 trades
        assertEquals("run_3", sortedTrades.get(2).runId()); // 10 trades

        // 12. Test Sorting by Win Rate DESC
        BacktestQueryFilters sortWinRateDesc = BacktestQueryFilters.builder()
            .sortBy("win_rate")
            .sortOrder("DESC")
            .build();
        List<BacktestRunSummary> sortedWinRate = store.list(sortWinRateDesc);
        assertEquals(3, sortedWinRate.size());
        assertEquals("run_2", sortedWinRate.get(0).runId()); // 70% win rate
        assertEquals("run_1", sortedWinRate.get(1).runId()); // 60% win rate
        assertEquals("run_3", sortedWinRate.get(2).runId()); // 50% win rate

        // 13. Test Sorting by Capital DESC
        BacktestQueryFilters sortCapitalDesc = BacktestQueryFilters.builder()
            .sortBy("capital")
            .sortOrder("DESC")
            .build();
        List<BacktestRunSummary> sortedCapital = store.list(sortCapitalDesc);
        assertEquals(3, sortedCapital.size());
        assertEquals("run_2", sortedCapital.get(0).runId()); // 20000.0
        assertEquals("run_1", sortedCapital.get(1).runId()); // 10000.0
        assertEquals("run_3", sortedCapital.get(2).runId()); // 5000.0
    }

    private BacktestRunDetails createSampleRun(
        String runId, String strategyId, String symbol, double sharpe, double pf, double pnl, double maxDrawdown, Instant createdAt, int totalTrades, double winRatePct, double initialCapital
    ) {
        return new BacktestRunDetails(
            runId,
            strategyId,
            symbol,
            Instant.now().minus(1, ChronoUnit.HOURS),
            Instant.now(),
            "{}",
            "hash_" + runId,
            initialCapital,
            initialCapital + pnl,
            pnl,
            pnl / 100.0,
            totalTrades,
            (int) (totalTrades * winRatePct / 100.0),
            totalTrades - (int) (totalTrades * winRatePct / 100.0),
            winRatePct,
            maxDrawdown,
            pnl / (totalTrades == 0 ? 1 : totalTrades),
            sharpe,
            sharpe + 0.3,
            pf,
            sharpe / (maxDrawdown == 0 ? 1 : maxDrawdown),
            10.0,
            20.0,
            "[]",
            createdAt
        );
    }
}
