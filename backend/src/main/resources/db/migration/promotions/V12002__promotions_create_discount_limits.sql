-- A store's manual discount limits in percent of the list price (D106, P44): up to free_limit_percent an
-- ORDER_DISCOUNT holder alone, up to max_percent with an approver. Working copy: frozen into the store's next
-- promotion release. A store without a row publishes the P44 defaults (10 / 50).
CREATE TABLE promotions.discount_limits
(
    store_id           UUID                     NOT NULL,
    free_limit_percent NUMERIC(5, 2)            NOT NULL,
    max_percent        NUMERIC(5, 2)            NOT NULL,
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    version            BIGINT                   NOT NULL,
    CONSTRAINT discount_limits_pk PRIMARY KEY (store_id),
    CONSTRAINT discount_limits_free_ck CHECK (free_limit_percent >= 0 AND free_limit_percent <= 100),
    CONSTRAINT discount_limits_max_ck CHECK (max_percent >= free_limit_percent AND max_percent <= 100)
);
