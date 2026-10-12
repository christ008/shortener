-- Clicks per link, per hour and per class of device, counted by the application in batches (ADR 0037). One row per
-- (code, hour, class) whose count the application adds to, so it needs no row per click and replicas need no coordination.
-- No foreign key to short_link: a code is counted only after it resolved, a link is never deleted, and a key checked on every
-- upsert would cost a read of short_link per row. The hour is the start of the hour, in UTC, and the class is one of three.
CREATE TABLE link_click_hourly
(
    short_code   TEXT        NOT NULL,
    hour         TIMESTAMPTZ NOT NULL,
    device_class TEXT        NOT NULL,
    clicks       BIGINT      NOT NULL,
    CONSTRAINT link_click_hourly_pkey PRIMARY KEY (short_code, hour, device_class),
    CONSTRAINT link_click_hourly_device_class CHECK (device_class IN ('desktop', 'mobile', 'bot')),
    CONSTRAINT link_click_hourly_clicks_positive CHECK (clicks > 0)
);

-- What the application role may do with it, stated next to the schema as V6 does for short_link: read it, add a row, and add to a
-- count. Not DELETE, not TRUNCATE, not moving a count to another link or hour. Where the role does not exist there is nothing to grant.
DO
$$
    BEGIN
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'shortener_app') THEN
            REVOKE ALL ON TABLE link_click_hourly FROM shortener_app;
            GRANT SELECT, INSERT ON TABLE link_click_hourly TO shortener_app;
            GRANT UPDATE (clicks) ON TABLE link_click_hourly TO shortener_app;
        END IF;
    END
$$;
