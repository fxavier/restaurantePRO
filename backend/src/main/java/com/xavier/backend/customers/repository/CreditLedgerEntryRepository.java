package com.xavier.backend.customers.repository;

import com.xavier.backend.customers.model.CreditLedgerEntry;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CreditLedgerEntryRepository extends JpaRepository<CreditLedgerEntry, UUID> {

    /** The balance: Σ DEBIT − Σ CREDIT, an index-only scan of {@code credit_ledger_entry_customer_ix} (D73). */
    @Query(nativeQuery = true, value = """
            SELECT coalesce(sum(CASE kind WHEN 'DEBIT' THEN amount ELSE -amount END), 0)
            FROM customers.credit_ledger_entry WHERE customer_id = :customerId""")
    BigDecimal balance(@Param("customerId") UUID customerId);
}
