DROP TABLE event_publication;

ALTER TABLE short_link DROP COLUMN id;
ALTER TABLE short_link DROP CONSTRAINT uk_short_link_short_code;
ALTER TABLE short_link ADD CONSTRAINT short_link_pkey PRIMARY KEY (short_code);
ALTER TABLE short_link ALTER COLUMN created_at SET DEFAULT now();
ALTER TABLE short_link
    ADD CONSTRAINT short_link_short_code_format CHECK (short_code ~ '^[A-Za-z0-9_-]{3,32}$'),
    ADD CONSTRAINT short_link_target_url_scheme CHECK (target_url ~* '^https?://');
