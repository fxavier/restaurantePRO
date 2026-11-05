package com.xavier.backend.customers.repository;

import com.xavier.backend.customers.model.CreditSettlement;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CreditSettlementRepository extends JpaRepository<CreditSettlement, UUID> {

    /** Every settlement or reopening that one of {@code entryIds} takes part in. */
    @Query("""
            select s from CreditSettlement s
            where s.creditEntryId in :entryIds or s.debitEntryId in :entryIds or s.reversalEntryId in :entryIds
            order by s.id""")
    List<CreditSettlement> involving(@Param("entryIds") Collection<UUID> entryIds);
}
