package com.xavier.backend.customers.repository;

import com.xavier.backend.customers.model.CreditLedgerEntry;
import com.xavier.backend.customers.model.EntryKind;
import com.xavier.backend.customers.model.EntrySource;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CreditLedgerEntryRepository extends JpaRepository<CreditLedgerEntry, UUID> {

    /** Settled minus reopened, per settlement row; summed per debit or per (credit, debit) pair. */
    String SIGNED_SETTLEMENT = "CASE WHEN s.reversal_entry_id IS NULL THEN s.amount ELSE -s.amount END";

    /** The balance: Σ DEBIT − Σ CREDIT, an index-only scan of {@code credit_ledger_entry_customer_ix} (D73). */
    @Query(nativeQuery = true, value = """
            SELECT coalesce(sum(CASE kind WHEN 'DEBIT' THEN amount ELSE -amount END), 0)
            FROM customers.credit_ledger_entry WHERE customer_id = :customerId""")
    BigDecimal balance(@Param("customerId") UUID customerId);

    Optional<CreditLedgerEntry> findBySourceTypeAndSourceIdAndKind(EntrySource sourceType, UUID sourceId,
                                                                   EntryKind kind);

    /** The customer's sale debits that are still open, oldest first (FIFO, D73). */
    @Query(nativeQuery = true, value = """
            SELECT d.id AS entryId, d.amount - coalesce(sum(""" + SIGNED_SETTLEMENT + """
            ), 0) AS amount
            FROM customers.credit_ledger_entry d
            LEFT JOIN customers.credit_settlement s ON s.debit_entry_id = d.id
            WHERE d.customer_id = :customerId AND d.source_type = 'CREDIT_CHARGE'
            GROUP BY d.id, d.amount, d.occurred_at
            HAVING d.amount - coalesce(sum(""" + SIGNED_SETTLEMENT + """
            ), 0) > 0
            ORDER BY d.occurred_at, d.id""")
    List<EntryAmount> openDebits(@Param("customerId") UUID customerId);

    /** The open amount of each of {@code ids} that is a sale debit of the customer (zero when fully settled). */
    @Query(nativeQuery = true, value = """
            SELECT d.id AS entryId, d.amount - coalesce(sum(""" + SIGNED_SETTLEMENT + """
            ), 0) AS amount
            FROM customers.credit_ledger_entry d
            LEFT JOIN customers.credit_settlement s ON s.debit_entry_id = d.id
            WHERE d.customer_id = :customerId AND d.source_type = 'CREDIT_CHARGE' AND d.id IN (:ids)
            GROUP BY d.id, d.amount""")
    List<EntryAmount> openAmountsOf(@Param("customerId") UUID customerId, @Param("ids") Collection<UUID> ids);

    /** What a CREDIT still has settled on each debit, the latest debit first (a refund reopens them in this order). */
    @Query(nativeQuery = true, value = """
            SELECT s.debit_entry_id AS entryId, sum(""" + SIGNED_SETTLEMENT + """
            ) AS amount
            FROM customers.credit_settlement s
            JOIN customers.credit_ledger_entry d ON d.id = s.debit_entry_id
            WHERE s.credit_entry_id = :creditId
            GROUP BY s.debit_entry_id, d.occurred_at
            HAVING sum(""" + SIGNED_SETTLEMENT + """
            ) > 0
            ORDER BY d.occurred_at DESC, s.debit_entry_id DESC""")
    List<EntryAmount> settledBy(@Param("creditId") UUID creditId);
}
