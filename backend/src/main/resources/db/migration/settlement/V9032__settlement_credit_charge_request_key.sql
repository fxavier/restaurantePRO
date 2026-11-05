-- The Idempotency-Key of the credit sale that reserved the charge (S29 review): a sale under a new key resumes a
-- RESERVED charge of the same customer and amount, but never a POSTED one (409 CREDIT_CHARGE_EXISTS); only the re-run
-- of its own sale (same key) gets a POSTED charge back. Charges reserved before this column stay NULL. Set once, at
-- insert: the immutability trigger already refuses any change to a column outside its mutable list.
ALTER TABLE settlement.credit_charge
    ADD COLUMN request_key VARCHAR(255);
