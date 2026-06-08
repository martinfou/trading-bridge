package com.martinfou.trading.backtest.persistence;

/**
 * Filter and sorting criteria for querying persisted backtest runs.
 */
public record BacktestQueryFilters(
    String symbol,
    String strategyId,
    Double minSharpe,
    Double minProfitFactor,
    String sortBy,
    String sortOrder, // "ASC" or "DESC"
    Integer limit,
    Integer offset
) {
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String symbol;
        private String strategyId;
        private Double minSharpe;
        private Double minProfitFactor;
        private String sortBy;
        private String sortOrder = "DESC";
        private Integer limit;
        private Integer offset;

        public Builder symbol(String symbol) {
            this.symbol = symbol;
            return this;
        }

        public Builder strategyId(String strategyId) {
            this.strategyId = strategyId;
            return this;
        }

        public Builder minSharpe(Double minSharpe) {
            this.minSharpe = minSharpe;
            return this;
        }

        public Builder minProfitFactor(Double minProfitFactor) {
            this.minProfitFactor = minProfitFactor;
            return this;
        }

        public Builder sortBy(String sortBy) {
            this.sortBy = sortBy;
            return this;
        }

        public Builder sortOrder(String sortOrder) {
            this.sortOrder = sortOrder;
            return this;
        }

        public Builder limit(Integer limit) {
            this.limit = limit;
            return this;
        }

        public Builder offset(Integer offset) {
            this.offset = offset;
            return this;
        }

        public BacktestQueryFilters build() {
            return new BacktestQueryFilters(
                symbol, strategyId, minSharpe, minProfitFactor, sortBy, sortOrder, limit, offset
            );
        }
    }
}
