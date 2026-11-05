-- D68 amendment (S28): a PARTITION draft, or the residual ORDER draft of a partitioned order, carries settlement's
-- allocated shares, checked within a bound instead of D59's exact recomputation. The flag is part of the draft's
-- content (a redraft with the other flag is DRAFT_MISMATCH). No row is updated (D93): every existing document,
-- R2's included, was drafted with exact D59 lines, which the DEFAULT records.
ALTER TABLE fiscal.fiscal_document
    ADD COLUMN amounts_allocated BOOLEAN NOT NULL DEFAULT false;
