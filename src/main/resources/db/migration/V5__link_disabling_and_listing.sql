ALTER TABLE short_link
    ADD COLUMN disabled_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN disabled_by TEXT,
    ADD CONSTRAINT short_link_disabled_consistent CHECK ((disabled_at IS NULL) = (disabled_by IS NULL));

CREATE INDEX short_link_created_at_idx ON short_link (created_at DESC, short_code COLLATE "C" DESC);
CREATE INDEX short_link_created_by_idx ON short_link (created_by, created_at DESC, short_code COLLATE "C" DESC);
