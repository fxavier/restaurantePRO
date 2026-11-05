package com.xavier.backend.settlement.reconciliation.repository;

import com.xavier.backend.settlement.model.Payment;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Reconciliation's reads of settlement's payments, refunds and attempts (PRD S31: it reads them through settlement
 * repositories and writes none of them). A {@link Repository} with no save or delete method.
 */
public interface PaymentReads extends Repository<Payment, UUID> {

    /** A store's payments of {@code methodCode} confirmed in {@code [from, to)}, with their refunds and references. */
    @Query("""
            select new com.xavier.backend.settlement.reconciliation.repository.CandidateRow(p.id, p.amount,
                coalesce((select sum(r.amount) from Refund r where r.paymentId = p.id), 0), p.currency,
                p.confirmedAt, a.providerReference, a.providerTransactionRef, a.authorizationCode)
            from Payment p, PaymentAttempt a
            where a.id = p.attemptId and p.storeId = :storeId and p.methodCode = :methodCode
                and p.confirmedAt >= :from and p.confirmedAt < :to""")
    List<CandidateRow> confirmedBetween(@Param("storeId") UUID storeId, @Param("methodCode") String methodCode,
                                        @Param("from") Instant from, @Param("to") Instant to);

    /** A store's payments of {@code methodCode} whose attempt reference or provider reference is one of {@code refs}. */
    @Query("""
            select new com.xavier.backend.settlement.reconciliation.repository.CandidateRow(p.id, p.amount,
                coalesce((select sum(r.amount) from Refund r where r.paymentId = p.id), 0), p.currency,
                p.confirmedAt, a.providerReference, a.providerTransactionRef, a.authorizationCode)
            from Payment p, PaymentAttempt a
            where a.id = p.attemptId and p.storeId = :storeId and p.methodCode = :methodCode
                and (a.providerReference in :refs or a.providerTransactionRef in :refs)""")
    List<CandidateRow> withReferences(@Param("storeId") UUID storeId, @Param("methodCode") String methodCode,
                                      @Param("refs") Collection<String> refs);
}
