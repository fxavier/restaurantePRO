package com.xavier.backend.cash.repository;

import com.xavier.backend.cash.model.CashClosing;
import com.xavier.backend.cash.model.CashClosingStatus;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CashClosingRepository extends JpaRepository<CashClosing, UUID> {

    boolean existsByRegisterIdAndCashDayIdAndStatusIn(UUID registerId, UUID cashDayId,
                                                      Collection<CashClosingStatus> statuses);

    boolean existsByRegisterIdAndCashDayIdAndDayRoundAndStatusIn(UUID registerId, UUID cashDayId, int dayRound,
                                                                 Collection<CashClosingStatus> statuses);

    Optional<CashClosing> findFirstByRegisterIdAndCashDayIdAndDayRoundOrderByAttemptNoDesc(UUID registerId,
                                                                                         UUID cashDayId,
                                                                                         int dayRound);

    List<CashClosing> findByCashDayIdAndStatus(UUID cashDayId, CashClosingStatus status);

    List<CashClosing> findByRegisterIdAndCashDayIdAndStatusIn(UUID registerId, UUID cashDayId,
                                                              Collection<CashClosingStatus> statuses);

    /** The closings, with their lines, of the store's days in a business-date range (reconciliation). */
    @Query("""
            select distinct c from CashClosing c left join fetch c.lines
            where c.storeId = :storeId and c.cashDayId in (
                select d.id from CashDay d where d.storeId = :storeId and d.businessDate between :from and :to)""")
    List<CashClosing> findWithLinesByStoreAndBusinessDates(@Param("storeId") UUID storeId,
                                                           @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** A store's closings, optionally of one cash day and/or in one status. */
    @Query("""
            select c from CashClosing c where c.storeId = :storeId
            and (:dayId is null or c.cashDayId = :dayId) and (:status is null or c.status = :status)""")
    Page<CashClosing> search(@Param("storeId") UUID storeId, @Param("dayId") UUID dayId,
                             @Param("status") CashClosingStatus status, Pageable pageable);

    /**
     * D150 CASH_DISCREPANCY, per store: the closings in {@code statuses} submitted or decided at or after {@code from}
     * whose Σ |difference| over their lines exceeds {@code threshold}.
     */
    @Query("""
            select new com.xavier.backend.cash.repository.StoreCashDiscrepancy(c.storeId, count(c), min(c.submittedAt))
            from CashClosing c
            where c.status in :statuses and (c.submittedAt >= :from or c.decidedAt >= :from)
            and (select sum(abs(l.differenceAmount)) from CashClosingLine l where l.closing = c) > :threshold
            group by c.storeId""")
    List<StoreCashDiscrepancy> discrepanciesSince(@Param("statuses") Collection<CashClosingStatus> statuses,
                                                  @Param("from") Instant from,
                                                  @Param("threshold") BigDecimal threshold);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CashClosing c where c.id = :id")
    Optional<CashClosing> findByIdForUpdate(@Param("id") UUID id);

    @Query("select c.cashDayId from CashClosing c where c.id = :id")
    Optional<UUID> findCashDayIdById(@Param("id") UUID id);
}
