-- What analytics_retention (V9) finds the oldest hour and the oldest day with, and deletes by, without reading whole tables. Built
-- concurrently, so the redirects and the flushes that write these tables are not blocked while they build; that needs a migration
-- outside a transaction (V10__analytics_retention_indexes.sql.conf).
CREATE INDEX CONCURRENTLY IF NOT EXISTS link_click_hourly_hour_idx ON link_click_hourly (hour);
CREATE INDEX CONCURRENTLY IF NOT EXISTS link_click_daily_day_idx ON link_click_daily (day);
CREATE INDEX CONCURRENTLY IF NOT EXISTS link_click_daily_device_day_idx ON link_click_daily_device (day);
