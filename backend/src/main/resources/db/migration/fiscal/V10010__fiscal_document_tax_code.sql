-- The tax summary of a rate names its lines' catalog tax code(s) (S33 review): reporting reads the code from the
-- document instead of deriving it from the order. Documents drafted before this column keep NULL (they are immutable
-- once ISSUED; reporting derives their code as before). Adding a column fires no row trigger.
ALTER TABLE fiscal.fiscal_document_tax
    ADD COLUMN tax_code VARCHAR(120);
