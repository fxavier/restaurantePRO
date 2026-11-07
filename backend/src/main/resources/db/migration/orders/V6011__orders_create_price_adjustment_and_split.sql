-- Discounts after confirmation are facts (D105, D106, D112). An adjustment sets a confirmed line's effective unit price
-- (D101: 0 <= effective <= list, per unit); the line's effective price is its latest adjustment's, else its snapshot.
-- A discount on part of a line first splits it: q units move to a new CONFIRMED line of the same order with the same
-- snapshot, no round and origin_split_id, so production and stock never see them twice (like a transfer, §8.2).
-- Net quantity = quantity − Σ voids − Σ transferred out − Σ split out.
CREATE TABLE orders.order_line_price_adjustment
(
    id                   UUID                     NOT NULL,
    order_id             UUID                     NOT NULL,
    line_id              UUID                     NOT NULL,
    seq                  INTEGER                  NOT NULL,
    kind                 VARCHAR(10)              NOT NULL,
    -- the line's net quantity the new price applies to when it was recorded (reporting's discount amount)
    quantity             NUMERIC(12, 3)           NOT NULL,
    percent              NUMERIC(7, 4),
    previous_unit_price  NUMERIC(19, 4)           NOT NULL,
    unit_price           NUMERIC(19, 4)           NOT NULL,
    currency             CHAR(3)                  NOT NULL,
    reward_code          VARCHAR(30),
    loyalty_entry_id     UUID,
    reason               VARCHAR(200)             NOT NULL,
    operator_employee_id UUID                     NOT NULL,
    approver_employee_id UUID,
    at                   TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT order_line_price_adjustment_pk PRIMARY KEY (id),
    CONSTRAINT order_line_price_adjustment_line_same_order_fk FOREIGN KEY (line_id, order_id)
        REFERENCES orders.order_line (id, order_id),
    CONSTRAINT order_line_price_adjustment_line_seq_uk UNIQUE (line_id, seq),
    CONSTRAINT order_line_price_adjustment_seq_ck CHECK (seq >= 1),
    CONSTRAINT order_line_price_adjustment_kind_ck CHECK (kind IN ('DISCOUNT', 'OFFER', 'REWARD', 'REVERSAL')),
    CONSTRAINT order_line_price_adjustment_quantity_positive_ck CHECK (quantity > 0),
    CONSTRAINT order_line_price_adjustment_unit_price_ck CHECK (unit_price >= 0),
    CONSTRAINT order_line_price_adjustment_percent_ck CHECK (percent IS NULL OR (percent > 0 AND percent <= 100)),
    -- DISCOUNT, OFFER and REWARD lower the price; REVERSAL raises it back (to at most the list price, checked in code)
    CONSTRAINT order_line_price_adjustment_direction_ck CHECK (
        (kind = 'REVERSAL' AND unit_price > previous_unit_price)
            OR (kind <> 'REVERSAL' AND unit_price < previous_unit_price)),
    -- an offer is price 0 and always approved (D106)
    CONSTRAINT order_line_price_adjustment_offer_ck CHECK (kind <> 'OFFER'
        OR (unit_price = 0 AND approver_employee_id IS NOT NULL)),
    -- a reward is paid by points: its code and ledger entry (D112)
    CONSTRAINT order_line_price_adjustment_reward_ck CHECK ((kind = 'REWARD')
        = (reward_code IS NOT NULL AND loyalty_entry_id IS NOT NULL)),
    CONSTRAINT order_line_price_adjustment_reason_ck CHECK (btrim(reason) <> ''),
    CONSTRAINT order_line_price_adjustment_self_approval_ck CHECK (approver_employee_id IS NULL
        OR approver_employee_id <> operator_employee_id)
);

CREATE INDEX order_line_price_adjustment_order_ix ON orders.order_line_price_adjustment (order_id);

CREATE TABLE orders.order_line_split
(
    id             UUID                     NOT NULL,
    order_id       UUID                     NOT NULL,
    source_line_id UUID                     NOT NULL,
    target_line_id UUID                     NOT NULL,
    quantity       NUMERIC(12, 3)           NOT NULL,
    adjustment_id  UUID                     NOT NULL,
    at             TIMESTAMP WITH TIME ZONE NOT NULL,
    employee_id    UUID                     NOT NULL,
    CONSTRAINT order_line_split_pk PRIMARY KEY (id),
    CONSTRAINT order_line_split_source_same_order_fk FOREIGN KEY (source_line_id, order_id)
        REFERENCES orders.order_line (id, order_id),
    CONSTRAINT order_line_split_target_same_order_fk FOREIGN KEY (target_line_id, order_id)
        REFERENCES orders.order_line (id, order_id),
    -- the split line, the split and its adjustment refer to each other: written in one transaction, checked at commit
    CONSTRAINT order_line_split_adjustment_fk FOREIGN KEY (adjustment_id)
        REFERENCES orders.order_line_price_adjustment (id) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT order_line_split_target_uk UNIQUE (target_line_id),
    -- target of order_line_origin_split_same_order_fk
    CONSTRAINT order_line_split_id_order_uk UNIQUE (id, order_id),
    CONSTRAINT order_line_split_quantity_positive_ck CHECK (quantity > 0),
    CONSTRAINT order_line_split_distinct_lines_ck CHECK (source_line_id <> target_line_id)
);

CREATE INDEX order_line_split_source_ix ON orders.order_line_split (source_line_id);

ALTER TABLE orders.order_line
    ADD COLUMN origin_split_id UUID,
    ADD CONSTRAINT order_line_origin_split_same_order_fk FOREIGN KEY (origin_split_id, order_id)
        REFERENCES orders.order_line_split (id, order_id) DEFERRABLE INITIALLY DEFERRED;

-- exactly one origin once confirmed: a round, a transfer from another order, or a split of a line of this order
ALTER TABLE orders.order_line DROP CONSTRAINT order_line_one_origin_ck;
ALTER TABLE orders.order_line ADD CONSTRAINT order_line_one_origin_ck
    CHECK (status = 'DRAFT' OR num_nonnulls(batch_id, origin_transfer_id, origin_split_id) = 1);
ALTER TABLE orders.order_line DROP CONSTRAINT order_line_draft_has_no_origin_ck;
ALTER TABLE orders.order_line ADD CONSTRAINT order_line_draft_has_no_origin_ck CHECK (status = 'CONFIRMED' OR (
    batch_id IS NULL AND origin_transfer_id IS NULL AND origin_split_id IS NULL AND confirmed_at IS NULL
        AND confirmed_by_employee_id IS NULL));

CREATE TRIGGER order_line_price_adjustment_immutable
    BEFORE UPDATE OR DELETE ON orders.order_line_price_adjustment
    FOR EACH ROW EXECUTE FUNCTION orders.reject_fact_change();
CREATE TRIGGER order_line_split_immutable
    BEFORE UPDATE OR DELETE ON orders.order_line_split
    FOR EACH ROW EXECUTE FUNCTION orders.reject_fact_change();
CREATE TRIGGER order_line_price_adjustment_no_truncate
    BEFORE TRUNCATE ON orders.order_line_price_adjustment
    FOR EACH STATEMENT EXECUTE FUNCTION orders.reject_fact_change();
CREATE TRIGGER order_line_split_no_truncate
    BEFORE TRUNCATE ON orders.order_line_split
    FOR EACH STATEMENT EXECUTE FUNCTION orders.reject_fact_change();
