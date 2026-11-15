-- Row-level security prerequisites for inventory (ADR 0039, D167, D169), before V13081's policies.
--
-- 1. stock_balance gets its own store_id (D167, P64): it is the one child read in bulk on a request path (valuation by
--    store) and locked on every posting, so it is protected as a STORE table instead of by a parent lookup. The store
--    is its warehouse's, backfilled here, and the composite foreign key (warehouse_id, store_id) → warehouse (id,
--    store_id) keeps the two from ever disagreeing. warehouse_id_store_uk is the unique key that foreign key needs
--    (D169: the one missing index).
-- 2. stock_transfer_line.value_shipped: the value the source's TRANSFER_OUT took (D113 as amended: the destination's
--    TRANSFER_IN carries exactly that value). The receiver of another store reads it here, on the transfer it sees at
--    both ends (D168), because the source's movements are invisible to it under row-level security. Backfilled from
--    the TRANSFER_OUT of every transfer shipped before this migration.
--
-- Safe on a live installation: the backend is stopped while Flyway migrates; every balance has a warehouse (existing
-- foreign key), so the backfill leaves no NULL; nothing but the new columns is written; stock_balance is a mutable,
-- reconcilable projection and stock_transfer_line is not a fact (D160: no migration updates an immutable row).
-- After this migration the R5 image cannot write a balance (no store_id in its insert): rolling back to R5 then needs
-- a restore of the pre-upgrade backup (deploy/README.md, amended by S65).

ALTER TABLE inventory.warehouse
    ADD CONSTRAINT warehouse_id_store_uk UNIQUE (id, store_id);

ALTER TABLE inventory.stock_balance
    ADD COLUMN store_id UUID;

UPDATE inventory.stock_balance b
SET store_id = w.store_id
FROM inventory.warehouse w
WHERE w.id = b.warehouse_id;

ALTER TABLE inventory.stock_balance
    ALTER COLUMN store_id SET NOT NULL;

ALTER TABLE inventory.stock_balance
    ADD CONSTRAINT stock_balance_warehouse_store_fk FOREIGN KEY (warehouse_id, store_id)
        REFERENCES inventory.warehouse (id, store_id);

ALTER TABLE inventory.stock_transfer_line
    ADD COLUMN value_shipped NUMERIC(19, 4);

UPDATE inventory.stock_transfer_line l
SET value_shipped = -m.value
FROM inventory.stock_transfer t
         JOIN inventory.stock_movement m
              ON m.source_type = 'TRANSFER' AND m.source_id = t.id AND m.type = 'TRANSFER_OUT'
                  AND m.warehouse_id = t.from_warehouse_id
WHERE t.id = l.transfer_id
  AND m.stock_item_id = l.stock_item_id
  AND t.status IN ('SHIPPED', 'RECEIVED');
