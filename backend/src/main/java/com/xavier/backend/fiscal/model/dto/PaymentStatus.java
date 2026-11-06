package com.xavier.backend.fiscal.model.dto;

import com.xavier.backend.shared.Money;

/**
 * How a document, a partition or an order stands against its gross (D97 rule 3), with P = paid (net of refunds),
 * C = credit (live charges) and G = gross. The one function both the document snapshot and settlement's live views
 * use, so they agree at issue.
 */
public enum PaymentStatus {
    /** P = 0, C = 0, G > 0. */
    UNPAID,
    /** C = 0, 0 < P < G. */
    PARTIALLY_PAID,
    /** C = 0, P ≥ G: a zero gross is paid too (§8.3 counts it SETTLED). */
    PAID,
    /** C = G: the whole document is a credit sale, the only one printed "A CRÉDITO". */
    ON_ACCOUNT,
    /** 0 < C < G: part on account, the rest paid or still outstanding. */
    MIXED;

    public static PaymentStatus of(Money gross, Money paid, Money credit) {
        if (credit.amount().signum() > 0) {
            return credit.amount().compareTo(gross.amount()) >= 0 ? ON_ACCOUNT : MIXED;
        }
        if (paid.amount().compareTo(gross.amount()) >= 0) {
            return PAID;
        }
        return paid.amount().signum() > 0 ? PARTIALLY_PAID : UNPAID;
    }
}
