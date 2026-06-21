-- AStockSelector complete SQLite schema
-- Date: 2026-06-17
-- Database file: market_cache.db
--
-- Scope:
-- 1. Runtime cache tables currently used by the Android app:
--    metadata, stocks, daily_bars, cache_update_failures
-- 2. Complete app-level tables for future SQLite migration:
--    signal snapshots, signal results, reasons, metrics, rule checks,
--    selected strategies, update logs, app update checks.
--
-- Note:
-- The current Android code still stores some app state in SharedPreferences.
-- This SQL is the complete database design if all app data is moved into SQLite.

PRAGMA foreign_keys = ON;

BEGIN TRANSACTION;

-- ============================================================
-- 1. Cache metadata
-- ============================================================

CREATE TABLE IF NOT EXISTS metadata (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

-- Runtime metadata keys:
-- schema_version
-- generated_at
-- stock_source
-- kline_source
-- cache_days
-- last_expected_trade_date
-- last_calendar_expected_trade_date
-- stock_count
-- daily_bar_count

-- ============================================================
-- 2. Stock master data
-- ============================================================

CREATE TABLE IF NOT EXISTS stocks (
    symbol TEXT PRIMARY KEY,
    code TEXT NOT NULL,
    ts_code TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    market TEXT NOT NULL CHECK (market IN ('MAIN', 'CHINEXT', 'STAR', 'BSE', 'UNKNOWN')),
    is_st INTEGER NOT NULL DEFAULT 0 CHECK (is_st IN (0, 1)),
    current_price REAL NOT NULL DEFAULT 0,
    current_amount REAL NOT NULL DEFAULT 0,
    source TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_stocks_ts_code
    ON stocks(ts_code);

CREATE INDEX IF NOT EXISTS idx_stocks_market
    ON stocks(market);

CREATE INDEX IF NOT EXISTS idx_stocks_is_st
    ON stocks(is_st);

CREATE INDEX IF NOT EXISTS idx_stocks_current_amount
    ON stocks(current_amount);

-- ============================================================
-- 3. Daily K-line cache
-- ============================================================

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
    PRIMARY KEY (ts_code, trade_date),
    FOREIGN KEY (ts_code) REFERENCES stocks(ts_code) ON UPDATE CASCADE ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_daily_bars_trade_date
    ON daily_bars(trade_date);

CREATE INDEX IF NOT EXISTS idx_daily_bars_ts_code
    ON daily_bars(ts_code);

CREATE INDEX IF NOT EXISTS idx_daily_bars_ts_date_desc
    ON daily_bars(ts_code, trade_date DESC);

CREATE INDEX IF NOT EXISTS idx_daily_bars_date_amount
    ON daily_bars(trade_date, amount);

CREATE TABLE IF NOT EXISTS cache_update_failures (
    ts_code TEXT PRIMARY KEY,
    symbol TEXT NOT NULL,
    name TEXT NOT NULL,
    retry_date TEXT NOT NULL,
    retry_count INTEGER NOT NULL DEFAULT 0,
    last_error TEXT NOT NULL DEFAULT '',
    last_failed_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (ts_code) REFERENCES stocks(ts_code) ON UPDATE CASCADE ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_cache_update_failures_retry
    ON cache_update_failures(retry_date, retry_count);

-- Cache cleanup rule used by the app:
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

-- ============================================================
-- 4. Preset strategy runtime config
-- ============================================================

CREATE TABLE IF NOT EXISTS strategy_config_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    rule_version TEXT NOT NULL,
    near_limit_ratio REAL NOT NULL DEFAULT 0.90,
    first_board_lookback INTEGER NOT NULL DEFAULT 20,
    volume_multiplier REAL NOT NULL DEFAULT 1.20,
    min_amount REAL NOT NULL DEFAULT 50000000,
    near_ma_pct REAL NOT NULL DEFAULT 0.05,
    max_first_board_ma_distance_pct REAL NOT NULL DEFAULT 0.10,
    max_nine_yang_rise_pct REAL NOT NULL DEFAULT 25.0,
    min_nine_yang_yang_count INTEGER NOT NULL DEFAULT 7,
    min_nine_yang_near_ma_count INTEGER NOT NULL DEFAULT 4,
    nine_yang_min_score INTEGER NOT NULL DEFAULT 85,
    game_kline_near_ma_pct REAL NOT NULL DEFAULT 0.03,
    rebound_ratio_threshold REAL NOT NULL DEFAULT 0.80,
    close_strength_threshold REAL NOT NULL DEFAULT 0.80,
    game_kline_volume_ratio REAL NOT NULL DEFAULT 1.20,
    game_kline_min_score INTEGER NOT NULL DEFAULT 85,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_strategy_config_rule_version
    ON strategy_config_history(rule_version);

-- ============================================================
-- 5. Selected preset strategies
-- ============================================================

CREATE TABLE IF NOT EXISTS selected_strategies (
    strategy_name TEXT PRIMARY KEY CHECK (
        strategy_name IN ('year_ma_first_board', 'nine_yang_buildup', 'game_k', 'low_level_start')
    ),
    display_name TEXT NOT NULL,
    selected INTEGER NOT NULL DEFAULT 1 CHECK (selected IN (0, 1)),
    sort_order INTEGER NOT NULL,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_selected_strategies_selected_order
    ON selected_strategies(selected, sort_order);

-- ============================================================
-- 6. Signal snapshots
-- ============================================================

CREATE TABLE IF NOT EXISTS signal_snapshots (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    data_source TEXT NOT NULL,
    status_text TEXT NOT NULL,
    cache_date TEXT NOT NULL DEFAULT '',
    expected_trade_date TEXT NOT NULL DEFAULT '',
    rule_key TEXT NOT NULL DEFAULT '',
    rule_version TEXT NOT NULL DEFAULT '',
    strategy_summary TEXT NOT NULL DEFAULT '',
    all_signal_count INTEGER NOT NULL DEFAULT 0,
    visible_signal_count INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_signal_snapshots_created_at
    ON signal_snapshots(created_at DESC);

CREATE INDEX IF NOT EXISTS idx_signal_snapshots_cache_rule
    ON signal_snapshots(cache_date, rule_key);

-- One row per displayed stock result in one snapshot.
CREATE TABLE IF NOT EXISTS signal_results (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    snapshot_id INTEGER NOT NULL,
    ts_code TEXT NOT NULL,
    trade_date TEXT NOT NULL,
    stock_name TEXT NOT NULL,
    market TEXT NOT NULL,
    merged_strategy TEXT NOT NULL,
    score INTEGER NOT NULL,
    level TEXT NOT NULL CHECK (level IN ('STRONG', 'NORMAL')),
    is_new INTEGER NOT NULL DEFAULT 0 CHECK (is_new IN (0, 1)),
    display_order INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (snapshot_id) REFERENCES signal_snapshots(id) ON DELETE CASCADE,
    FOREIGN KEY (ts_code) REFERENCES stocks(ts_code) ON UPDATE CASCADE ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS idx_signal_results_snapshot_order
    ON signal_results(snapshot_id, display_order);

CREATE INDEX IF NOT EXISTS idx_signal_results_ts_code
    ON signal_results(ts_code);

CREATE INDEX IF NOT EXISTS idx_signal_results_trade_date
    ON signal_results(trade_date);

CREATE INDEX IF NOT EXISTS idx_signal_results_is_new
    ON signal_results(snapshot_id, is_new, display_order);

-- One row per strategy that a displayed result matched.
CREATE TABLE IF NOT EXISTS signal_result_strategies (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    result_id INTEGER NOT NULL,
    strategy_name TEXT NOT NULL,
    score INTEGER NOT NULL,
    level TEXT NOT NULL CHECK (level IN ('STRONG', 'NORMAL')),
    buy_trigger TEXT NOT NULL DEFAULT '',
    stop_loss TEXT NOT NULL DEFAULT '',
    sort_order INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (result_id) REFERENCES signal_results(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_signal_result_strategies_result
    ON signal_result_strategies(result_id, sort_order);

CREATE INDEX IF NOT EXISTS idx_signal_result_strategies_name
    ON signal_result_strategies(strategy_name);

CREATE TABLE IF NOT EXISTS signal_reasons (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    result_strategy_id INTEGER NOT NULL,
    reason_text TEXT NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (result_strategy_id) REFERENCES signal_result_strategies(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_signal_reasons_strategy
    ON signal_reasons(result_strategy_id, sort_order);

CREATE TABLE IF NOT EXISTS signal_metrics (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    result_strategy_id INTEGER NOT NULL,
    metric_label TEXT NOT NULL,
    metric_value TEXT NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (result_strategy_id) REFERENCES signal_result_strategies(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_signal_metrics_strategy
    ON signal_metrics(result_strategy_id, sort_order);

CREATE TABLE IF NOT EXISTS signal_rule_checks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    result_strategy_id INTEGER NOT NULL,
    check_label TEXT NOT NULL,
    passed INTEGER NOT NULL CHECK (passed IN (0, 1)),
    sort_order INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (result_strategy_id) REFERENCES signal_result_strategies(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_signal_rule_checks_strategy
    ON signal_rule_checks(result_strategy_id, sort_order);

CREATE INDEX IF NOT EXISTS idx_signal_rule_checks_passed
    ON signal_rule_checks(result_strategy_id, passed);

CREATE TABLE IF NOT EXISTS new_signal_codes (
    snapshot_id INTEGER NOT NULL,
    ts_code TEXT NOT NULL,
    PRIMARY KEY (snapshot_id, ts_code),
    FOREIGN KEY (snapshot_id) REFERENCES signal_snapshots(id) ON DELETE CASCADE,
    FOREIGN KEY (ts_code) REFERENCES stocks(ts_code) ON UPDATE CASCADE ON DELETE CASCADE
);

-- ============================================================
-- 7. Update logs
-- ============================================================

CREATE TABLE IF NOT EXISTS market_update_runs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    update_type TEXT NOT NULL CHECK (update_type IN ('smart', 'cache_incremental', 'cache_full', 'direct_real')),
    started_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TEXT,
    status TEXT NOT NULL DEFAULT 'running' CHECK (status IN ('running', 'success', 'failed', 'cancelled')),
    data_source TEXT NOT NULL DEFAULT '',
    expected_trade_date TEXT NOT NULL DEFAULT '',
    cache_date_before TEXT NOT NULL DEFAULT '',
    cache_date_after TEXT NOT NULL DEFAULT '',
    stock_total INTEGER NOT NULL DEFAULT 0,
    kline_success INTEGER NOT NULL DEFAULT 0,
    kline_failed INTEGER NOT NULL DEFAULT 0,
    sina_success INTEGER NOT NULL DEFAULT 0,
    tencent_fallback INTEGER NOT NULL DEFAULT 0,
    signal_count INTEGER NOT NULL DEFAULT 0,
    elapsed_ms INTEGER NOT NULL DEFAULT 0,
    error_message TEXT NOT NULL DEFAULT ''
);

CREATE INDEX IF NOT EXISTS idx_market_update_runs_started
    ON market_update_runs(started_at DESC);

CREATE INDEX IF NOT EXISTS idx_market_update_runs_status
    ON market_update_runs(status, started_at DESC);

CREATE TABLE IF NOT EXISTS market_update_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id INTEGER NOT NULL,
    event_time TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    progress_text TEXT NOT NULL,
    completed INTEGER NOT NULL DEFAULT 0,
    total INTEGER NOT NULL DEFAULT 0,
    success_count INTEGER NOT NULL DEFAULT 0,
    failed_count INTEGER NOT NULL DEFAULT 0,
    fallback_count INTEGER NOT NULL DEFAULT 0,
    remaining_text TEXT NOT NULL DEFAULT '',
    FOREIGN KEY (run_id) REFERENCES market_update_runs(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_market_update_events_run
    ON market_update_events(run_id, event_time);

-- ============================================================
-- 8. Program update checks
-- ============================================================

CREATE TABLE IF NOT EXISTS app_update_checks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    checked_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    current_version_code INTEGER NOT NULL,
    current_version_name TEXT NOT NULL,
    latest_version_code INTEGER NOT NULL,
    latest_version_name TEXT NOT NULL,
    apk_url TEXT NOT NULL,
    apk_sha256 TEXT NOT NULL DEFAULT '',
    apk_size INTEGER NOT NULL DEFAULT 0,
    release_notes TEXT NOT NULL DEFAULT '',
    has_update INTEGER NOT NULL CHECK (has_update IN (0, 1)),
    status TEXT NOT NULL DEFAULT 'success' CHECK (status IN ('success', 'failed')),
    error_message TEXT NOT NULL DEFAULT ''
);

CREATE INDEX IF NOT EXISTS idx_app_update_checks_checked
    ON app_update_checks(checked_at DESC);

CREATE INDEX IF NOT EXISTS idx_app_update_checks_has_update
    ON app_update_checks(has_update, checked_at DESC);

-- ============================================================
-- 9. Generic app settings
-- ============================================================

CREATE TABLE IF NOT EXISTS app_settings (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL,
    value_type TEXT NOT NULL DEFAULT 'text' CHECK (value_type IN ('text', 'int', 'real', 'bool', 'json')),
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Suggested setting keys:
-- near_ma_pct
-- min_amount
-- last_selected_tab
-- battery_optimization_prompted
-- notification_permission_prompted

-- ============================================================
-- 10. Convenience views
-- ============================================================

CREATE VIEW IF NOT EXISTS latest_signal_snapshot AS
SELECT *
FROM signal_snapshots
ORDER BY created_at DESC, id DESC
LIMIT 1;

CREATE VIEW IF NOT EXISTS latest_signal_results AS
SELECT
    r.*
FROM signal_results r
JOIN latest_signal_snapshot s ON s.id = r.snapshot_id
ORDER BY r.is_new DESC, r.display_order ASC, r.score DESC;

CREATE VIEW IF NOT EXISTS stock_latest_bars AS
SELECT b.*
FROM daily_bars b
JOIN (
    SELECT ts_code, MAX(trade_date) AS max_trade_date
    FROM daily_bars
    GROUP BY ts_code
) latest
    ON latest.ts_code = b.ts_code
   AND latest.max_trade_date = b.trade_date;

COMMIT;
