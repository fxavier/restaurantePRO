-- How a pending fiscal correction was cleared (D96 rule 6): CORRECTED, with the reference of the corrective document
-- the market's correction process produced. One per adjustment, and only a PENDING_CORRECTION adjustment has one (an
-- ABSORBED cent needs no correction). R3 has no accept or write-off action. A fact: append-only.
CREATE TABLE settlement.tax_correction_resolution
(
    id                   UUID                     NOT NULL,
    adjustment_id        UUID                     NOT NULL,
    action               VARCHAR(20)              NOT NULL,
    correction_reference VARCHAR(100)             NOT NULL,
    reason               VARCHAR(500)             NOT NULL,
    actor_kind           VARCHAR(10)              NOT NULL,
    actor_id             UUID,
    at                   TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT tax_correction_resolution_pk PRIMARY KEY (id),
    CONSTRAINT tax_correction_resolution_adjustment_fk FOREIGN KEY (adjustment_id)
        REFERENCES settlement.tax_rounding_adjustment (id),
    CONSTRAINT tax_correction_resolution_adjustment_uk UNIQUE (adjustment_id),
    CONSTRAINT tax_correction_resolution_action_ck CHECK (action IN ('CORRECTED')),
    CONSTRAINT tax_correction_resolution_reference_ck CHECK (btrim(correction_reference) <> ''),
    CONSTRAINT tax_correction_resolution_reason_ck CHECK (btrim(reason) <> ''),
    CONSTRAINT tax_correction_resolution_actor_ck CHECK (
        (actor_kind = 'SYSTEM' AND actor_id IS NULL)
            OR (actor_kind IN ('ACCOUNT', 'EMPLOYEE') AND actor_id IS NOT NULL))
);

CREATE FUNCTION settlement.require_pending_correction() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NOT EXISTS (SELECT 1
                   FROM settlement.tax_rounding_adjustment
                   WHERE id = NEW.adjustment_id
                     AND kind = 'PENDING_CORRECTION') THEN
        RAISE EXCEPTION 'tax rounding adjustment % is not a PENDING_CORRECTION', NEW.adjustment_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tax_correction_resolution_pending_only
    BEFORE INSERT
    ON settlement.tax_correction_resolution
    FOR EACH ROW
EXECUTE FUNCTION settlement.require_pending_correction();
CREATE TRIGGER tax_correction_resolution_append_only
    BEFORE UPDATE OR DELETE
    ON settlement.tax_correction_resolution
    FOR EACH ROW
EXECUTE FUNCTION settlement.reject_fact_change();
CREATE TRIGGER tax_correction_resolution_no_truncate
    BEFORE TRUNCATE
    ON settlement.tax_correction_resolution
    FOR EACH STATEMENT
EXECUTE FUNCTION settlement.reject_fact_change();
