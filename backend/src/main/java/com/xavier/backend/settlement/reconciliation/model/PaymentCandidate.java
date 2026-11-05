package com.xavier.backend.settlement.reconciliation.model;

import com.xavier.backend.shared.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * A payment of the statement's store and method as the matcher sees it: {@code net} is the payment less its refunds
 * (S30), the references and the authorization code are its attempt's, and {@code matchedEarlier} says an earlier batch
 * already matched it (MATCHED or AMOUNT_MISMATCH).
 */
public record PaymentCandidate(UUID paymentId, Money net, Instant confirmedAt, String providerReference,
                               String providerTransactionRef, String authorizationCode, boolean matchedEarlier) {
}
