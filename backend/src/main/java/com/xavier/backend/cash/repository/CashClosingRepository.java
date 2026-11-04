package com.xavier.backend.cash.repository;

import com.xavier.backend.cash.model.CashClosing;
import com.xavier.backend.cash.model.CashClosingStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CashClosingRepository extends JpaRepository<CashClosing, UUID> {

    boolean existsByRegisterIdAndCashDayIdAndStatusIn(UUID registerId, UUID cashDayId,
                                                      Collection<CashClosingStatus> statuses);

    Optional<CashClosing> findFirstByRegisterIdAndCashDayIdOrderByAttemptNoDesc(UUID registerId, UUID cashDayId);

    List<CashClosing> findByCashDayIdAndStatus(UUID cashDayId, CashClosingStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CashClosing c where c.id = :id")
    Optional<CashClosing> findByIdForUpdate(@Param("id") UUID id);

    @Query("select c.storeId from CashClosing c where c.id = :id")
    Optional<UUID> findStoreIdById(@Param("id") UUID id);
}
