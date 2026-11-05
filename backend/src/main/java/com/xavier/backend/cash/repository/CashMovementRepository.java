package com.xavier.backend.cash.repository;

import com.xavier.backend.cash.model.CashMovement;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CashMovementRepository extends JpaRepository<CashMovement, UUID> {

    Optional<CashMovement> findBySourceTypeAndSourceId(String sourceType, UUID sourceId);

    boolean existsBySessionIdAndMethodCode(UUID sessionId, String methodCode);

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

    /** Expected amount and adjustments per day, register and method of the store's days in a range. */
    @Query("""
            select new com.xavier.backend.cash.repository.DayRegisterMethodTotal(m.cashDayId, m.registerId,
                m.methodCode,
                sum(case when m.direction = com.xavier.backend.cash.model.MovementDirection.IN then m.amount
                         else -m.amount end),
                sum(case when m.type <> com.xavier.backend.cash.model.MovementType.ADJUSTMENT then 0
                         when m.direction = com.xavier.backend.cash.model.MovementDirection.IN then m.amount
                         else -m.amount end))
            from CashMovement m join CashDay d on d.id = m.cashDayId
            where m.storeId = :storeId and d.storeId = :storeId and d.businessDate between :from and :to
            group by m.cashDayId, m.registerId, m.methodCode""")
    List<DayRegisterMethodTotal> totalsByDayRegisterAndMethod(@Param("storeId") UUID storeId,
                                                              @Param("from") LocalDate from,
                                                              @Param("to") LocalDate to);
}
