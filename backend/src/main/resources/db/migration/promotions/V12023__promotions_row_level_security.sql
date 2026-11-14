-- Row-level security (ADR 0039, D167, D168). A store's manual discount limits and its promotion releases belong to
-- that store. Promotions (with their scopes, windows and store lists) and the loyalty program are company-wide
-- (ADR 0030): a loyalty entry's store_id is where it originated, not its owner.
SELECT shared.rls_protect_store_table('promotions.discount_limits');
SELECT shared.rls_protect_store_table('promotions.promotion_release');

SELECT shared.rls_register_unscoped('promotions.promotion', 'Company-wide promotions');
SELECT shared.rls_register_unscoped('promotions.promotion_scope', 'Company-wide promotions: what a promotion covers');
SELECT shared.rls_register_unscoped('promotions.promotion_window', 'Company-wide promotions: when a promotion runs');
SELECT shared.rls_register_unscoped('promotions.promotion_store',
                                    'Company-wide promotions: the stores a promotion runs in (configuration)');
SELECT shared.rls_register_unscoped('promotions.loyalty_program_version', 'Company-wide loyalty program (ADR 0030)');
SELECT shared.rls_register_unscoped('promotions.loyalty_program_scope', 'Company-wide loyalty program (ADR 0030)');
SELECT shared.rls_register_unscoped('promotions.loyalty_reward', 'Company-wide loyalty program (ADR 0030)');
SELECT shared.rls_register_unscoped('promotions.loyalty_account', 'Company-wide loyalty members (ADR 0030)');
SELECT shared.rls_register_unscoped('promotions.loyalty_allocation', 'Company-wide loyalty ledger (ADR 0030)');
SELECT shared.rls_register_unscoped('promotions.loyalty_point_entry',
                                    'Company-wide loyalty ledger (ADR 0030); its store_id is the origin of an entry');
