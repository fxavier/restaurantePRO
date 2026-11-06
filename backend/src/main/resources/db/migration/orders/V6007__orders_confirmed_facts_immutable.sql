-- Confirmed facts are never edited or deleted (spec §8, r2-decisions §4 orders, A15). The database itself refuses an
-- UPDATE or DELETE of a CONFIRMED order line, and any change to a void, a transfer or a transfer line. A DRAFT line
-- may still change, including DRAFT -> CONFIRMED. Follows the audit and fiscal trigger pattern (D18, D61).
CREATE FUNCTION orders.reject_confirmed_line_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF OLD.status = 'CONFIRMED' THEN
        RAISE EXCEPTION 'orders.order_line %: a CONFIRMED line is immutable (% refused)', OLD.id, TG_OP
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER order_line_confirmed_immutable
    BEFORE UPDATE OR DELETE
    ON orders.order_line
    FOR EACH ROW
EXECUTE FUNCTION orders.reject_confirmed_line_change();

CREATE FUNCTION orders.reject_fact_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION '%.%: a fact is immutable (% refused)', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER order_line_void_immutable
    BEFORE UPDATE OR DELETE ON orders.order_line_void
    FOR EACH ROW EXECUTE FUNCTION orders.reject_fact_change();
CREATE TRIGGER order_transfer_immutable
    BEFORE UPDATE OR DELETE ON orders.order_transfer
    FOR EACH ROW EXECUTE FUNCTION orders.reject_fact_change();
CREATE TRIGGER order_transfer_line_immutable
    BEFORE UPDATE OR DELETE ON orders.order_transfer_line
    FOR EACH ROW EXECUTE FUNCTION orders.reject_fact_change();

-- TRUNCATE skips row triggers, so it is refused outright on the tables that hold facts.
CREATE TRIGGER order_line_no_truncate
    BEFORE TRUNCATE ON orders.order_line
    FOR EACH STATEMENT EXECUTE FUNCTION orders.reject_fact_change();
CREATE TRIGGER order_line_void_no_truncate
    BEFORE TRUNCATE ON orders.order_line_void
    FOR EACH STATEMENT EXECUTE FUNCTION orders.reject_fact_change();
CREATE TRIGGER order_transfer_no_truncate
    BEFORE TRUNCATE ON orders.order_transfer
    FOR EACH STATEMENT EXECUTE FUNCTION orders.reject_fact_change();
CREATE TRIGGER order_transfer_line_no_truncate
    BEFORE TRUNCATE ON orders.order_transfer_line
    FOR EACH STATEMENT EXECUTE FUNCTION orders.reject_fact_change();
