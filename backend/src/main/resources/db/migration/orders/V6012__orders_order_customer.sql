-- The customer of an order (D130): set at open or by POST /orders/{id}/customer, checked through CustomersApi (D6: by
-- id, no cross-module FK). Loyalty finds the member through it (D111, D112).
ALTER TABLE orders.order_header ADD COLUMN customer_id UUID;

CREATE INDEX order_header_customer_ix ON orders.order_header (customer_id) WHERE customer_id IS NOT NULL;
