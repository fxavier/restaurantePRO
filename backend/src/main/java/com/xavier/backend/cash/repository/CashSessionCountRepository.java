package com.xavier.backend.cash.repository;

import com.xavier.backend.cash.model.CashSessionCount;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CashSessionCountRepository extends JpaRepository<CashSessionCount, UUID> {

    /** Counted amount per method of a register on a cash day, over all its sessions. */
    @Query("""
            select new com.xavier.backend.cash.repository.MethodTotal(c.methodCode, sum(c.amount))
            from CashSessionCount c
            where c.sessionId in (select s.id from CashSession s where s.registerId = :registerId
                                  and s.cashDayId = :dayId)
            group by c.methodCode""")
    List<MethodTotal> countedByRegisterAndDay(@Param("registerId") UUID registerId, @Param("dayId") UUID dayId);
}
