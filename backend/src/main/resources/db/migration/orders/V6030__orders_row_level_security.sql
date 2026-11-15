-- Row-level security on store_id (ADR 0039, D167, D168): every orders table is protected by the V1008 helpers, so a
-- connection reads and writes only the rows of its store scope and no row ever moves to another store.
--
-- CHILD tables have no store_id: a policy looks up the parent through a single-column in-module foreign key. Four
-- children reached their parent only through composite same-order foreign keys (to order_line or order_transfer);
-- they get the direct key here, which every existing row already satisfies (its line belongs to that order, and the
-- line's order exists), so the parent of each child is explicit in the schema and in the coverage test.
ALTER TABLE orders.order_line_component
    ADD CONSTRAINT order_line_component_order_fk FOREIGN KEY (order_id) REFERENCES orders.order_header (id);
ALTER TABLE orders.order_line_price_adjustment
    ADD CONSTRAINT order_line_price_adjustment_order_fk FOREIGN KEY (order_id) REFERENCES orders.order_header (id);
ALTER TABLE orders.order_line_split
    ADD CONSTRAINT order_line_split_order_fk FOREIGN KEY (order_id) REFERENCES orders.order_header (id);
ALTER TABLE orders.order_transfer_line
    ADD CONSTRAINT order_transfer_line_transfer_fk FOREIGN KEY (transfer_id) REFERENCES orders.order_transfer (id);

-- STORE (5): the row belongs to one store.
SELECT shared.rls_protect_store_table('orders.order_header');
SELECT shared.rls_protect_store_table('orders.order_batch');
SELECT shared.rls_protect_store_table('orders.order_transfer');
SELECT shared.rls_protect_store_table('orders.delivery');
SELECT shared.rls_protect_store_table('orders.delivery_zone');

-- CHILD (7): visible and writable when the parent's store is in scope.
SELECT shared.rls_protect_child_table('orders.order_line', 'order_id', 'orders.order_header', 'id');
SELECT shared.rls_protect_child_table('orders.order_line_component', 'order_id', 'orders.order_header', 'id');
SELECT shared.rls_protect_child_table('orders.order_line_price_adjustment', 'order_id', 'orders.order_header', 'id');
SELECT shared.rls_protect_child_table('orders.order_line_split', 'order_id', 'orders.order_header', 'id');
SELECT shared.rls_protect_child_table('orders.order_line_void', 'order_id', 'orders.order_header', 'id');
SELECT shared.rls_protect_child_table('orders.order_transfer_line', 'transfer_id', 'orders.order_transfer', 'id');
SELECT shared.rls_protect_child_table('orders.delivery_event', 'order_id', 'orders.delivery', 'order_id');
