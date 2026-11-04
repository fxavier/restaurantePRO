package com.xavier.backend.cash.repository;

import com.xavier.backend.cash.model.CashDay;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CashDayRepository extends JpaRepository<CashDay, UUID> {

    /** The day of {@code businessDate}, created OPEN if absent; two first sessions of the day never collide. */
    @Modifying
    @NativeQuery("""
            INSERT INTO cash.cash_day (store_id, business_date, opened_at) VALUES (:storeId, :businessDate, :now)
            ON CONFLICT (store_id, business_date) DO NOTHING""")
    void createIfAbsent(@Param("storeId") UUID storeId, @Param("businessDate") LocalDate businessDate,
                        @Param("now") Instant now);

    Optional<CashDay> findByStoreIdAndBusinessDate(UUID storeId, LocalDate businessDate);

    /** {@code FOR SHARE}: a session opening waits for, and then sees, a concurrent day close (D50 §7.8). */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select d from CashDay d where d.storeId = :storeId and d.businessDate = :businessDate")
    Optional<CashDay> findForShare(@Param("storeId") UUID storeId, @Param("businessDate") LocalDate businessDate);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from CashDay d where d.storeId = :storeId and d.businessDate = :businessDate")
    Optional<CashDay> findForUpdate(@Param("storeId") UUID storeId, @Param("businessDate") LocalDate businessDate);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from CashDay d where d.id = :id")
    Optional<CashDay> findByIdForUpdate(@Param("id") UUID id);

    @Query("select d.storeId from CashDay d where d.id = :id")
    Optional<UUID> findStoreIdById(@Param("id") UUID id);
}
