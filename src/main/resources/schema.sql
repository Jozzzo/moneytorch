CREATE TABLE IF NOT EXISTS season (
    id         INT PRIMARY KEY,
    label      VARCHAR(64),
    name       VARCHAR(128),
    is_current BOOLEAN,
    sort_order INT
);

CREATE TABLE IF NOT EXISTS item (
    id         VARCHAR(32) PRIMARY KEY,
    name_en    VARCHAR(255),
    name_ru    VARCHAR(255),
    name_zh    VARCHAR(255),
    main       VARCHAR(64),
    main_label VARCHAR(64),
    sub        VARCHAR(128),
    sub_label  VARCHAR(128),
    tlidb_slug VARCHAR(255)
);

-- ts = epoch millis UTC
CREATE TABLE IF NOT EXISTS price_point (
    season_id INT         NOT NULL,
    source    VARCHAR(16) NOT NULL,
    item_id   VARCHAR(32) NOT NULL,
    ts        BIGINT      NOT NULL,
    price     DOUBLE      NOT NULL,
    PRIMARY KEY (season_id, source, item_id, ts)
);

CREATE TABLE IF NOT EXISTS backfill_done (
    season_id INT         NOT NULL,
    source    VARCHAR(16) NOT NULL,
    item_id   VARCHAR(32) NOT NULL,
    PRIMARY KEY (season_id, source, item_id)
);
