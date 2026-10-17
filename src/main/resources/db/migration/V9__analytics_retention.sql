-- Retention of the click counts (ADR 0043): hours for 90 days, then folded into days per class of device, and days for 730 days.
-- Days are UTC days, as everywhere in the counts.

-- Clicks per link, UTC day and class of device, for the days whose hours are no longer kept. A day is either here or in
-- link_click_hourly, never in both: the function below moves a whole day at once. No foreign key, as in V7.
CREATE TABLE link_click_daily_device
(
    short_code   TEXT   NOT NULL,
    day          DATE   NOT NULL,
    device_class TEXT   NOT NULL,
    clicks       BIGINT NOT NULL,
    CONSTRAINT link_click_daily_device_pkey PRIMARY KEY (short_code, day, device_class),
    CONSTRAINT link_click_daily_device_class CHECK (device_class IN ('desktop', 'mobile', 'bot')),
    CONSTRAINT link_click_daily_device_clicks_positive CHECK (clicks > 0)
);

-- One step of retention, called by the application until it reports nothing left to do. Each call does at most one day of each
-- kind of work, so it stays within the application role's statement_timeout (5 s): it folds the oldest UTC day of hours older than
-- 90 days into link_click_daily_device and deletes those hours, and deletes the oldest day older than 730 days from each daily table.
--
-- It runs as its owner (SECURITY DEFINER), so the role that calls it needs no DELETE: it can run this, with its rules fixed here and
-- no argument to change them, and nothing else. The search path is fixed, so a caller cannot put its own objects in front.
-- Instances that call it at the same time do not repeat the work: the one that does not get the lock reports busy and returns.
CREATE FUNCTION analytics_retention(OUT busy BOOLEAN, OUT hours_folded BIGINT, OUT rows_deleted BIGINT)
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS
$$
DECLARE
    today CONSTANT DATE := (now() AT TIME ZONE 'UTC')::DATE;
    hours_kept_from CONSTANT TIMESTAMPTZ := (today - 90)::TIMESTAMP AT TIME ZONE 'UTC';
    days_kept_from CONSTANT DATE := today - 730;
    oldest_hour TIMESTAMPTZ;
    oldest_day DATE;
    day_start TIMESTAMPTZ;
    deleted BIGINT;
BEGIN
    busy := NOT pg_try_advisory_xact_lock(hashtext('analytics_retention'));
    hours_folded := 0;
    rows_deleted := 0;
    IF busy THEN
        RETURN;
    END IF;

    SELECT min(hour) INTO oldest_hour FROM link_click_hourly;
    IF oldest_hour < hours_kept_from THEN
        day_start := date_trunc('day', oldest_hour, 'UTC');
        WITH folded AS (
            DELETE FROM link_click_hourly
            WHERE hour >= day_start AND hour < day_start + INTERVAL '1 day'
            RETURNING short_code, device_class, clicks
        ),
        written AS (
            INSERT INTO link_click_daily_device (short_code, day, device_class, clicks)
            SELECT short_code, (day_start AT TIME ZONE 'UTC')::DATE, device_class, sum(clicks)
            FROM folded
            GROUP BY short_code, device_class
            ON CONFLICT (short_code, day, device_class) DO UPDATE SET clicks = link_click_daily_device.clicks + EXCLUDED.clicks
        )
        SELECT count(*) INTO hours_folded FROM folded;
    END IF;

    SELECT min(day) INTO oldest_day FROM link_click_daily_device;
    IF oldest_day < days_kept_from THEN
        DELETE FROM link_click_daily_device WHERE day = oldest_day;
        GET DIAGNOSTICS deleted = ROW_COUNT;
        rows_deleted := rows_deleted + deleted;
    END IF;

    SELECT min(day) INTO oldest_day FROM link_click_daily;
    IF oldest_day < days_kept_from THEN
        DELETE FROM link_click_daily WHERE day = oldest_day;
        GET DIAGNOSTICS deleted = ROW_COUNT;
        rows_deleted := rows_deleted + deleted;
    END IF;
END
$$;

-- A function can be called by anyone unless that is taken away. The application role may read the folded days and run retention;
-- it cannot write to the folded days or delete anything itself. Where the role does not exist there is nothing to grant.
REVOKE ALL ON FUNCTION analytics_retention() FROM PUBLIC;
DO
$$
    BEGIN
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'shortener_app') THEN
            REVOKE ALL ON TABLE link_click_daily_device FROM shortener_app;
            GRANT SELECT ON TABLE link_click_daily_device TO shortener_app;
            GRANT EXECUTE ON FUNCTION analytics_retention() TO shortener_app;
        END IF;
    END
$$;
