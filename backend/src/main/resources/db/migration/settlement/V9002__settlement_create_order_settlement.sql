-- The settlement lock row of an order (r2-decisions §4 settlement, D51). It stores no balance: the financial state
-- is derived from the order's due and the payments (spec §8.3). SELECT ... FOR UPDATE on it serializes the payments,
-- the document issue and the close of one order. Lock order: order_settlement -> cash_session -> fiscal.
CREATE TABLE settlement.order_settlement
(
    order_id   UUID                     NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT order_settlement_pk PRIMARY KEY (order_id)
);
