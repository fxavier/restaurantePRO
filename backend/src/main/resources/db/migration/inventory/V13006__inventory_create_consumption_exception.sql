-- A line or component that could not be consumed (D116): no recipe, no route, an inactive stock item, or a
-- tracking rule that changed. Visible and reprocessable, never a silent skip. Written by S47; the status counts the
-- open ones per store.
CREATE TABLE inventory.consumption_exception
(
    id          UUID                     NOT NULL,
    store_id    UUID                     NOT NULL,
    source_type VARCHAR(30)              NOT NULL,
    source_id   UUID                     NOT NULL,
    item_id     UUID,
    kind        VARCHAR(25)              NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_at TIMESTAMP WITH TIME ZONE,
    resolution  TEXT,
    CONSTRAINT consumption_exception_pk PRIMARY KEY (id),
    CONSTRAINT consumption_exception_source_uk UNIQUE (source_type, source_id, kind),
    CONSTRAINT consumption_exception_kind_ck CHECK (kind IN ('NO_RECIPE', 'NO_ROUTE', 'INACTIVE_STOCK_ITEM',
                                                             'NOT_TRACKED_CHANGED'))
);
CREATE INDEX consumption_exception_open_ix ON inventory.consumption_exception (store_id) WHERE resolved_at IS NULL;
