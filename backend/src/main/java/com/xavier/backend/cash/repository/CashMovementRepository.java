package com.xavier.backend.cash.repository;

import com.xavier.backend.cash.model.CashMovement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CashMovementRepository extends JpaRepository<CashMovement, UUID> {

    Optional<CashMovement> findBySourceTypeAndSourceId(String sourceType, UUID sourceId);

    /** Expected amount per method of a session: Σ IN − Σ OUT. */
    @Query("""
            select new com.xavier.backend.cash.repository.MethodTotal(m.methodCode,
                sum(case when m.direction = com.xavier.backend.cash.model.MovementDirection.IN then m.amount
                         else -m.amount end))
            from CashMovement m where m.sessionId = :sessionId group by m.methodCode order by m.methodCode""")
    List<MethodTotal> expectedBySession(@Param("sessionId") UUID sessionId);

    /** Expected amount per method of a register on a cash day, over all its sessions. */
    @Query("""
            select new com.xavier.backend.cash.repository.MethodTotal(m.methodCode,
                sum(case when m.direction = com.xavier.backend.cash.model.MovementDirection.IN then m.amount
                         else -m.amount end))
            from CashMovement m where m.registerId = :registerId and m.cashDayId = :dayId
            group by m.methodCode""")
    List<MethodTotal> expectedByRegisterAndDay(@Param("registerId") UUID registerId, @Param("dayId") UUID dayId);
}
