-- R4 delivery (D127): DELIVERY is an order mode with no table (order_header_table_iff_dine_in_ck already refuses one)
-- and a customer. DDL only: no order row is updated (D135).
ALTER TABLE orders.order_header DROP CONSTRAINT order_header_mode_ck;
ALTER TABLE orders.order_header
    ADD CONSTRAINT order_header_mode_ck CHECK (mode IN ('DINE_IN', 'COUNTER', 'TAKEAWAY', 'DELIVERY'));
ALTER TABLE orders.order_header
    ADD CONSTRAINT order_header_delivery_customer_ck CHECK (mode <> 'DELIVERY' OR customer_id IS NOT NULL);
