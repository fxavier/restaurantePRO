-- The POS order panel reads the kitchen state of an order's rounds (ADR 0044, GET /pos/production-tickets/rounds):
-- the order's tickets, by order. Leads with tenant_id (D180 "Indexes"); tickets without an order (test pages,
-- receipt slips, drawer kicks) are left out of the index.
CREATE INDEX production_ticket_order_ix ON production.production_ticket (tenant_id, order_id)
    WHERE order_id IS NOT NULL;
