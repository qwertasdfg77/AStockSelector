-- AStockSelector runtime SQLite schema
-- Version: 0.3.6 / schema_version 2
-- Database file: market_cache.db
--
-- This file documents the tables created by CacheMarketRepository.
-- Strategy selection, filter settings, and the last displayed snapshot remain
-- in SharedPreferences and are intentionally not duplicated here.

BEGIN TRANSACTION;

CREATE TABLE IF NOT EXISTS metadata (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

-- Runtime metadata keys:
-- schema_version
-- generated_at
-- stock_source
-- kline_source
-- price_mode
-- cache_days
-- last_expected_trade_date
-- last_calendar_expected_trade_date
-- expected_trade_date_resolved_at
-- stock_count
-- daily_bar_count

CREATE TABLE IF NOT EXISTS stocks (
    symbol TEXT PRIMARY KEY,
    code TEXT NOT NULL,
    ts_code TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    market TEXT NOT NULL,
    is_st INTEGER NOT NULL DEFAULT 0,
    current_price REAL NOT NULL DEFAULT 0,
    current_amount REAL NOT NULL DEFAULT 0,
    source TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS daily_bars (
    ts_code TEXT NOT NULL,
    trade_date TEXT NOT NULL,
    open REAL NOT NULL,
    high REAL NOT NULL,
    low REAL NOT NULL,
    close REAL NOT NULL,
    pre_close REAL NOT NULL,
    pct_chg REAL NOT NULL,
    volume REAL NOT NULL,
    amount REAL NOT NULL,
    source TEXT NOT NULL,
    PRIMARY KEY (ts_code, trade_date)
);

CREATE INDEX IF NOT EXISTS idx_daily_bars_trade_date
    ON daily_bars(trade_date);

CREATE INDEX IF NOT EXISTS idx_daily_bars_ts_code
    ON daily_bars(ts_code);

CREATE TABLE IF NOT EXISTS cache_update_failures (
    ts_code TEXT PRIMARY KEY,
    symbol TEXT NOT NULL,
    name TEXT NOT NULL,
    retry_date TEXT NOT NULL,
    retry_count INTEGER NOT NULL DEFAULT 0,
    last_error TEXT NOT NULL DEFAULT '',
    last_failed_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_cache_update_failures_retry
    ON cache_update_failures(retry_date, retry_count);

CREATE TABLE IF NOT EXISTS strategy_evaluations (
    rule_key TEXT NOT NULL,
    ts_code TEXT NOT NULL,
    trade_date TEXT NOT NULL,
    signal_count INTEGER NOT NULL DEFAULT 0,
    evaluated_at TEXT NOT NULL,
    PRIMARY KEY (rule_key, ts_code, trade_date)
);

CREATE INDEX IF NOT EXISTS idx_strategy_evaluations_date
    ON strategy_evaluations(rule_key, trade_date);

CREATE TABLE IF NOT EXISTS strategy_signal_results (
    rule_key TEXT NOT NULL,
    ts_code TEXT NOT NULL,
    trade_date TEXT NOT NULL,
    strategy TEXT NOT NULL,
    score INTEGER NOT NULL,
    level TEXT NOT NULL,
    reasons_json TEXT NOT NULL,
    metrics_json TEXT NOT NULL,
    buy_trigger TEXT NOT NULL,
    stop_loss TEXT NOT NULL,
    rule_checks_json TEXT NOT NULL,
    is_new INTEGER NOT NULL DEFAULT 0,
    evaluated_at TEXT NOT NULL,
    PRIMARY KEY (rule_key, ts_code, trade_date, strategy)
);

CREATE INDEX IF NOT EXISTS idx_strategy_signal_results_date
    ON strategy_signal_results(rule_key, trade_date);

CREATE INDEX IF NOT EXISTS idx_strategy_signal_results_stock
    ON strategy_signal_results(rule_key, ts_code);

COMMIT;

-- Retention policy used by the app:
-- Keep the most recent 270 distinct market trade dates.
--
-- DELETE FROM daily_bars
-- WHERE trade_date < (
--     SELECT MIN(trade_date)
--     FROM (
--         SELECT DISTINCT trade_date
--         FROM daily_bars
--         ORDER BY trade_date DESC
--         LIMIT 270
--     )
-- );
