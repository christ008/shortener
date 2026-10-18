-- Retention of the click counts (ADR 0043): hours for 90 days, then days for 730, then months, kept for ever. Every day is a UTC day.
--
--   link_click_hourly          (V7)  clicks per link, hour and class                    the last 90 days
--   link_click_daily_device    here  clicks per link, day and class                     90 to 730 days old
--   link_click_monthly_device  here  clicks per link, month and class, one per day       older than 730 days
--   link_click_daily           (V8)  visitors per link, day and referrer or language     the last 730 days
--   link_click_monthly         here  visitors per link, month and referrer or language   older than 730 days
--
-- A day is in exactly one table of each kind: the function below moves a whole day at a time. No foreign keys, as in V7.

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

-- One row a month instead of one a day: the row header and the index entry are paid once a month, which measured about eight times
-- smaller than daily rows for the same counts. Element n is day n of the month; the days a month does not have stay 0.
CREATE TABLE link_click_monthly_device
(
    short_code   TEXT     NOT NULL,
    month        DATE     NOT NULL,
    device_class TEXT     NOT NULL,
    clicks       BIGINT[] NOT NULL,
    CONSTRAINT link_click_monthly_device_pkey PRIMARY KEY (short_code, month, device_class),
    CONSTRAINT link_click_monthly_device_month CHECK (month = date_trunc('month', month)::DATE),
    CONSTRAINT link_click_monthly_device_class CHECK (device_class IN ('desktop', 'mobile', 'bot')),
    CONSTRAINT link_click_monthly_device_days CHECK (cardinality(clicks) = 31)
);

-- The referrer and language of a month, summed: older than 730 days they are kept by the month, not by the day.
CREATE TABLE link_click_monthly
(
    short_code TEXT   NOT NULL,
    month      DATE   NOT NULL,
    dimension  TEXT   NOT NULL,
    value      TEXT   NOT NULL,
    clicks     BIGINT NOT NULL,
    CONSTRAINT link_click_monthly_pkey PRIMARY KEY (short_code, month, dimension, value),
    CONSTRAINT link_click_monthly_month CHECK (month = date_trunc('month', month)::DATE),
    CONSTRAINT link_click_monthly_dimension CHECK (dimension IN ('referrer', 'language')),
    CONSTRAINT link_click_monthly_value_length CHECK (length(value) BETWEEN 1 AND 255),
    CONSTRAINT link_click_monthly_clicks_positive CHECK (clicks > 0)
);

-- Clicks per link, UTC day and class, wherever the day is kept. What the application reads a window of days from. The reader's own
-- privileges apply (security_invoker), so a view gives no one more than the tables do.
CREATE VIEW link_click_day WITH (security_invoker = true) AS
SELECT short_code, (hour AT TIME ZONE 'UTC')::DATE AS day, device_class, sum(clicks)::BIGINT AS clicks
FROM link_click_hourly
GROUP BY short_code, (hour AT TIME ZONE 'UTC')::DATE, device_class
UNION ALL
SELECT short_code, day, device_class, clicks
FROM link_click_daily_device
UNION ALL
SELECT m.short_code, m.month + (d.n::INTEGER - 1) AS day, m.device_class, d.clicks
FROM link_click_monthly_device m
         CROSS JOIN LATERAL unnest(m.clicks) WITH ORDINALITY AS d(clicks, n)
WHERE d.clicks > 0;

-- Visitors per link, referrer or language, over a period of days: a day of link_click_daily, or a month of link_click_monthly. What
-- the application reads a breakdown from; a period counts when it overlaps the window asked for.
CREATE VIEW link_click_breakdown WITH (security_invoker = true) AS
SELECT short_code, day AS first_day, day AS last_day, dimension, value, clicks
FROM link_click_daily
UNION ALL
SELECT short_code, month, (month + INTERVAL '1 month' - INTERVAL '1 day')::DATE, dimension, value, clicks
FROM link_click_monthly;

-- One step of retention, called by the application until it reports nothing left to do. Each call moves at most one day of each kind,
-- so it stays within the application role's statement_timeout (5 s):
--   - the oldest UTC day of hours older than 90 days, summed per class into link_click_daily_device;
--   - the oldest day older than 730 days of link_click_daily_device, into its element of link_click_monthly_device;
--   - the oldest day older than 730 days of link_click_daily, summed into link_click_monthly.
-- Nothing is lost: what leaves one table is added to the next, in the same statement.
--
-- It runs as its owner (SECURITY DEFINER), so the role that calls it needs no DELETE: it can run this, with its rules fixed here and no
-- argument to change them, and nothing else. The search path is fixed, so a caller cannot put its own objects in front. Instances that
-- call it at the same time do not repeat the work: the one that does not get the lock reports busy and returns.
CREATE FUNCTION analytics_retention(OUT busy BOOLEAN, OUT hours_folded BIGINT, OUT days_folded BIGINT)
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
    day_of_month INTEGER;
BEGIN
    busy := NOT pg_try_advisory_xact_lock(hashtext('analytics_retention'));
    hours_folded := 0;
    days_folded := 0;
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
        day_of_month := extract(DAY FROM oldest_day)::INTEGER;
        -- The day's count goes into its element of a month of zeros, and is added element by element to a month already there.
        WITH folded AS (
            DELETE FROM link_click_daily_device WHERE day = oldest_day
            RETURNING short_code, device_class, clicks
        )
        INSERT INTO link_click_monthly_device (short_code, month, device_class, clicks)
        SELECT short_code, date_trunc('month', oldest_day)::DATE, device_class,
               array_fill(0::BIGINT, ARRAY [day_of_month - 1]) || clicks || array_fill(0::BIGINT, ARRAY [31 - day_of_month])
        FROM folded
        ON CONFLICT (short_code, month, device_class) DO UPDATE SET clicks = (
            SELECT array_agg(held + added ORDER BY n)
            FROM unnest(link_click_monthly_device.clicks, EXCLUDED.clicks) WITH ORDINALITY AS days(held, added, n)
        );
        days_folded := days_folded + 1;
    END IF;

    SELECT min(day) INTO oldest_day FROM link_click_daily;
    IF oldest_day < days_kept_from THEN
        WITH folded AS (
            DELETE FROM link_click_daily WHERE day = oldest_day
            RETURNING short_code, dimension, value, clicks
        )
        INSERT INTO link_click_monthly (short_code, month, dimension, value, clicks)
        SELECT short_code, date_trunc('month', oldest_day)::DATE, dimension, value, sum(clicks)
        FROM folded
        GROUP BY short_code, dimension, value
        ON CONFLICT (short_code, month, dimension, value) DO UPDATE SET clicks = link_click_monthly.clicks + EXCLUDED.clicks;
        days_folded := days_folded + 1;
    END IF;
END
$$;

-- The function belongs to shortener_retention where that role exists (deploy/postgres/bootstrap.sql), which holds exactly what the
-- function does: read and move the counts down the tables above, and nothing on short_link. No one may delete a monthly count.
--
-- The application role may read every table and view of the counts and run retention. It cannot write the folded tables or delete
-- anything itself, and no one else may run the function: a function can be called by anyone unless that is taken away.
REVOKE ALL ON FUNCTION analytics_retention() FROM PUBLIC;
DO
$$
    BEGIN
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'shortener_retention') THEN
            GRANT SELECT, DELETE ON TABLE link_click_hourly TO shortener_retention;
            GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE link_click_daily_device, link_click_daily TO shortener_retention;
            GRANT SELECT, INSERT, UPDATE ON TABLE link_click_monthly_device, link_click_monthly TO shortener_retention;
            ALTER FUNCTION analytics_retention() OWNER TO shortener_retention;
        END IF;
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'shortener_app') THEN
            REVOKE ALL ON TABLE link_click_daily_device, link_click_monthly_device, link_click_monthly FROM shortener_app;
            GRANT SELECT ON TABLE link_click_daily_device, link_click_monthly_device, link_click_monthly TO shortener_app;
            GRANT SELECT ON TABLE link_click_day, link_click_breakdown TO shortener_app;
            GRANT EXECUTE ON FUNCTION analytics_retention() TO shortener_app;
        END IF;
    END
$$;
