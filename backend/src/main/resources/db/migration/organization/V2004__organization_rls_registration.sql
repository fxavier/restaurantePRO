-- Row-level security (ADR 0039, D167): organization's tables are UNSCOPED. Terminals are read by device token before
-- login, stores before any scope exists, and the company profile is company-wide.
SELECT shared.rls_register_unscoped('organization.company_profile', 'Company-wide: one company per installation');
SELECT shared.rls_register_unscoped('organization.store',
                                    'The stores themselves: read before a scope exists (login, activity checks)');
SELECT shared.rls_register_unscoped('organization.terminal',
                                    'Read before login by device token; its store_id is where it is enrolled');
