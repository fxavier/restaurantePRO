-- The chosen components of a MENU line (D108): unpriced child records for production routing and stock. A DRAFT menu
-- carries its choices here (item, variant, quantity); confirmation freezes them with the item code, description and
-- destination of the catalog release the line is priced from. Immutable once its line is CONFIRMED.
CREATE TABLE orders.order_line_component
(
    id          UUID           NOT NULL,
    order_id    UUID           NOT NULL,
    line_id     UUID           NOT NULL,
    group_code  VARCHAR(30)    NOT NULL,
    item_id     UUID           NOT NULL,
    variant_id  UUID,
    item_code   VARCHAR(30),
    description VARCHAR(120),
    quantity    NUMERIC(12, 3) NOT NULL,
    destination VARCHAR(30),
    position    INTEGER        NOT NULL,
    CONSTRAINT order_line_component_pk PRIMARY KEY (id),
    CONSTRAINT order_line_component_line_same_order_fk FOREIGN KEY (line_id, order_id)
        REFERENCES orders.order_line (id, order_id),
    CONSTRAINT order_line_component_line_position_uk UNIQUE (line_id, position),
    CONSTRAINT order_line_component_quantity_positive_ck CHECK (quantity > 0),
    CONSTRAINT order_line_component_position_ck CHECK (position >= 0)
);

CREATE INDEX order_line_component_order_ix ON orders.order_line_component (order_id);

-- A component of a CONFIRMED line is a fact. One of a DRAFT may change or go with its draft; a component may not be
-- added to a CONFIRMED line either. Freezing (set item_code, description) happens before the line's own update.
CREATE FUNCTION orders.reject_confirmed_component_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    line_status VARCHAR(10);
BEGIN
    SELECT status INTO line_status FROM orders.order_line
    WHERE id = CASE WHEN TG_OP = 'INSERT' THEN NEW.line_id ELSE OLD.line_id END;
    IF line_status = 'CONFIRMED' THEN
        RAISE EXCEPTION 'orders.order_line_component: the line is CONFIRMED, its components are immutable (% refused)',
            TG_OP USING ERRCODE = 'restrict_violation';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER order_line_component_confirmed_immutable
    BEFORE INSERT OR UPDATE OR DELETE
    ON orders.order_line_component
    FOR EACH ROW
EXECUTE FUNCTION orders.reject_confirmed_component_change();

CREATE TRIGGER order_line_component_no_truncate
    BEFORE TRUNCATE ON orders.order_line_component
    FOR EACH STATEMENT EXECUTE FUNCTION orders.reject_fact_change();
