-- Where the visitors of a link came from and which language they asked for, per link and UTC day, counted by the application in
-- batches (ADR 0037). One row per (code, day, dimension, value) whose count the application adds to. Visitors only: bots are never
-- counted here. A referrer is a host name, '(direct)' or '(other)'; a language is an ISO 639-1 code or '(unknown)'. A value in
-- parentheses cannot be a host name or a code, so the two kinds never meet. Each instance admits a bounded number of hosts per link
-- and day and counts the rest as '(other)', so a forged Referer cannot grow this table. No foreign key, as in V7.
CREATE TABLE link_click_daily
(
    short_code TEXT   NOT NULL,
    day        DATE   NOT NULL,
    dimension  TEXT   NOT NULL,
    value      TEXT   NOT NULL,
    clicks     BIGINT NOT NULL,
    CONSTRAINT link_click_daily_pkey PRIMARY KEY (short_code, day, dimension, value),
    CONSTRAINT link_click_daily_dimension CHECK (dimension IN ('referrer', 'language')),
    CONSTRAINT link_click_daily_value_length CHECK (length(value) BETWEEN 1 AND 255),
    CONSTRAINT link_click_daily_clicks_positive CHECK (clicks > 0)
);

-- What the application role may do with it, as V7 states for the hourly counts: read it, add a row, and add to a count. Not DELETE,
-- not TRUNCATE, not moving a count to another link, day or value. Where the role does not exist there is nothing to grant.
DO
$$
    BEGIN
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'shortener_app') THEN
            REVOKE ALL ON TABLE link_click_daily FROM shortener_app;
            GRANT SELECT, INSERT ON TABLE link_click_daily TO shortener_app;
            GRANT UPDATE (clicks) ON TABLE link_click_daily TO shortener_app;
        END IF;
    END
$$;
