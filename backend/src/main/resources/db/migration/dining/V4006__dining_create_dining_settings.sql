-- Per-store dining settings (P47): the map shows "reserved" from reservation_window_minutes before the start, and a
-- BOOKED reservation becomes NO_SHOW no_show_grace_minutes after it. A store without a row uses the defaults.
CREATE TABLE dining.dining_settings
(
    store_id                   UUID                     NOT NULL,
    reservation_window_minutes INTEGER                  NOT NULL DEFAULT 120,
    no_show_grace_minutes      INTEGER                  NOT NULL DEFAULT 30,
    updated_at                 TIMESTAMP WITH TIME ZONE NOT NULL,
    version                    BIGINT                   NOT NULL,
    CONSTRAINT dining_settings_pk PRIMARY KEY (store_id),
    CONSTRAINT dining_settings_window_ck CHECK (reservation_window_minutes BETWEEN 1 AND 1440),
    CONSTRAINT dining_settings_grace_ck CHECK (no_show_grace_minutes BETWEEN 0 AND 1440)
);
