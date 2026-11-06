package com.xavier.backend.settlement.model;

/**
 * A credit charge's state (D74): RESERVED holds the customer's limit while the target's document is issued; POSTED put
 * the amount on the customer's ledger (final); RELEASED gave the limit back before the document was issued (final).
 */
public enum CreditChargeStatus {
    RESERVED,
    POSTED,
    RELEASED;

    /** RESERVED and POSTED charges count as "crédito autorizado" in the financial state (§8.3, D74). */
    public boolean isLive() {
        return this != RELEASED;
    }
}
