-- Operating units of the company (spec §2, §8.2). Timezone validity is checked in code (Java region ids).
CREATE TABLE organization.store
(
    id                  UUID                     NOT NULL,
    code                VARCHAR(20)              NOT NULL,
    name                VARCHAR(120)             NOT NULL,
    timezone            VARCHAR(64)              NOT NULL,
    business_day_cutoff TIME                     NOT NULL DEFAULT '05:00',
    active              BOOLEAN                  NOT NULL,
    version             BIGINT                   NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT store_pk PRIMARY KEY (id),
    CONSTRAINT store_code_uk UNIQUE (code),
    CONSTRAINT store_code_not_blank_ck CHECK (btrim(code) <> ''),
    CONSTRAINT store_name_not_blank_ck CHECK (btrim(name) <> ''),
    CONSTRAINT store_timezone_not_blank_ck CHECK (btrim(timezone) <> '')
);
