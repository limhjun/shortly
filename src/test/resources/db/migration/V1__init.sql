-- short_url (H2-compatible version)
CREATE SEQUENCE IF NOT EXISTS short_url_seq
    START WITH 100000
    INCREMENT BY 1;

CREATE TABLE short_url (
    id            BIGINT       PRIMARY KEY DEFAULT nextval('short_url_seq'),
    code          VARCHAR(11)  NOT NULL UNIQUE,
    original_url  VARCHAR(2048) NOT NULL,
    created_at    TIMESTAMP NOT NULL,
    expires_at    TIMESTAMP
);

-- click_event
CREATE TABLE click_event (
    id                 BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    short_url_id       BIGINT       NOT NULL,
    clicked_at         TIMESTAMP NOT NULL,
    ip_hash            CHAR(64)     NOT NULL,
    referrer           VARCHAR(2048),
    user_agent_family  VARCHAR(50)
);

CREATE INDEX ix_click_url_time ON click_event (short_url_id, clicked_at);
CREATE INDEX ix_click_time     ON click_event (clicked_at);
