-- Reporting pulls a store's voids by the order's business day (OrdersApi.voidIds, D84): order_header is found
-- through order_header_store_business_day_ix, its voids through this index.
CREATE INDEX order_line_void_order_ix ON orders.order_line_void (order_id);
