package com.xavier.backend.cash.repository;

import com.xavier.backend.cash.model.CashSession;
import com.xavier.backend.cash.model.CashSessionStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CashSessionRepository extends JpaRepository<CashSession, UUID> {

    Optional<CashSession> findByStoreIdAndOperatorEmployeeIdAndStatus(UUID storeId, UUID operatorEmployeeId,
                                                                      CashSessionStatus status);

    boolean existsByRegisterIdAndStatus(UUID registerId, CashSessionStatus status);

    boolean existsByRegisterIdAndCashDayIdAndStatus(UUID registerId, UUID cashDayId, CashSessionStatus status);

    boolean existsByRegisterIdAndCashDayId(UUID registerId, UUID cashDayId);

    boolean existsByCashDayIdAndStatus(UUID cashDayId, CashSessionStatus status);

    @Query("select s.id from CashSession s where s.cashDayId = :dayId and s.status = :status order by s.id")
    List<UUID> findIdsByCashDayIdAndStatus(@Param("dayId") UUID dayId, @Param("status") CashSessionStatus status);

    /** The ids of the store's sessions whose cash day's business date is in {@code [from, to]}. */
    @Query("""
            select s.id from CashSession s, CashDay d
            where d.id = s.cashDayId and s.storeId = :storeId and d.businessDate between :from and :to""")
    List<UUID> findIdsOfStoreBetween(@Param("storeId") UUID storeId, @Param("from") java.time.LocalDate from,
                                     @Param("to") java.time.LocalDate to);

    @Query("select distinct s.registerId from CashSession s where s.cashDayId = :dayId")
    List<UUID> findRegisterIdsByCashDayId(@Param("dayId") UUID dayId);

    @Query("select x.storeId from CashSession x where x.id = :id")
    Optional<UUID> findStoreIdById(@Param("id") UUID id);
}
