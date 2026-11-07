-- D122a amendment (S49 review): starting an opening count before a scheduled go-live brings it forward to the
-- start of the count. An activation stays a fact otherwise: the only change allowed is an earlier active_from, with
-- every other column unchanged (the caller only moves one still in the future); deletes stay refused.
DROP TRIGGER inventory_activation_append_only ON inventory.inventory_activation;

CREATE FUNCTION inventory.guard_inventory_activation() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'UPDATE'
        AND NEW.active_from < OLD.active_from
        AND NEW.store_id = OLD.store_id
        AND NEW.activated_at = OLD.activated_at
        AND NEW.activated_by_account_id IS NOT DISTINCT FROM OLD.activated_by_account_id THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'inventory.inventory_activation: only a scheduled go-live can be brought forward (%)', TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER inventory_activation_append_only
    BEFORE UPDATE OR DELETE
    ON inventory.inventory_activation
    FOR EACH ROW
EXECUTE FUNCTION inventory.guard_inventory_activation();
