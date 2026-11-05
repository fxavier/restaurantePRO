package com.xavier.backend.customers.repository;

import com.xavier.backend.customers.model.CreditLedgerEntry;
import com.xavier.backend.customers.model.EntryKind;
import com.xavier.backend.customers.model.EntrySource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CreditLedgerEntryRepository extends JpaRepository<CreditLedgerEntry, UUID> {

    /** Settled minus reopened, per settlement row; summed per debit or per (credit, debit) pair. */
    String SIGNED_SETTLEMENT = "CASE WHEN s.reversal_entry_id IS NULL THEN s.amount ELSE -s.amount END";

    /**
     * Open sale debits as of {@code :asOf} (only settlements by entries up to then count), aged in days, per customer
     * with a debt of at least {@code :minBalance}. Σ open debits = the balance, because every CREDIT is fully
     * settled and every refund fully reopens (V11003 triggers).
     */
    String DEBTS = """
            WITH settled AS (
                SELECT s.debit_entry_id,
                       sum(CASE WHEN s.reversal_entry_id IS NULL THEN s.amount ELSE -s.amount END) AS amount
                FROM customers.credit_settlement s
                JOIN customers.credit_ledger_entry x ON x.id = coalesce(s.reversal_entry_id, s.credit_entry_id)
                WHERE x.occurred_at <= CAST(:asOf AS timestamptz)
                GROUP BY s.debit_entry_id),
            open_debit AS (
                SELECT d.customer_id, d.currency, d.occurred_at, d.amount - coalesce(st.amount, 0) AS open
                FROM customers.credit_ledger_entry d
                LEFT JOIN settled st ON st.debit_entry_id = d.id
                WHERE d.source_type = 'CREDIT_CHARGE' AND d.occurred_at <= CAST(:asOf AS timestamptz)),
            debt AS (
                SELECT customer_id, min(currency) AS currency, sum(open) AS balance,
                       coalesce(sum(open) FILTER (
                           WHERE occurred_at > CAST(:asOf AS timestamptz) - INTERVAL '31 days'), 0) AS d0,
                       coalesce(sum(open) FILTER (
                           WHERE occurred_at <= CAST(:asOf AS timestamptz) - INTERVAL '31 days'
                             AND occurred_at > CAST(:asOf AS timestamptz) - INTERVAL '61 days'), 0) AS d31,
                       coalesce(sum(open) FILTER (
                           WHERE occurred_at <= CAST(:asOf AS timestamptz) - INTERVAL '61 days'
                             AND occurred_at > CAST(:asOf AS timestamptz) - INTERVAL '91 days'), 0) AS d61,
                       coalesce(sum(open) FILTER (
                           WHERE occurred_at <= CAST(:asOf AS timestamptz) - INTERVAL '91 days'), 0) AS d91
                FROM open_debit
                GROUP BY customer_id
                HAVING sum(open) > 0 AND sum(open) >= :minBalance)
            """;

    @Query(nativeQuery = true, value = DEBTS + """
            SELECT d.customer_id AS customerId, c.name AS name, c.tax_id AS taxId, d.currency AS currency,
                   d.balance AS balance, d.d0 AS days0To30, d.d31 AS days31To60, d.d61 AS days61To90,
                   d.d91 AS over90
            FROM debt d JOIN customers.customer c ON c.id = d.customer_id
            ORDER BY c.name, c.id""",
            countQuery = DEBTS + "SELECT count(*) FROM debt")
    Page<DebtAging> debts(@Param("asOf") Instant asOf, @Param("minBalance") BigDecimal minBalance, Pageable pageable);

    /** The balance before {@code :from}: the opening balance of a statement. */
    @Query(nativeQuery = true, value = """
            SELECT coalesce(sum(CASE kind WHEN 'DEBIT' THEN amount ELSE -amount END), 0)
            FROM customers.credit_ledger_entry WHERE customer_id = :customerId AND occurred_at < :from""")
    BigDecimal balanceBefore(@Param("customerId") UUID customerId, @Param("from") Instant from);

    List<CreditLedgerEntry> findByCustomerIdAndOccurredAtBetweenOrderByOccurredAtAscIdAsc(UUID customerId,
                                                                                          Instant from, Instant to);

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
