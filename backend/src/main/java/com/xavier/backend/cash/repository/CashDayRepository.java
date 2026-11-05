package com.xavier.backend.cash.repository;

import com.xavier.backend.cash.model.CashDay;
import com.xavier.backend.cash.model.CashDayStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
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

    /** With {@code OPEN}: served by the partial index {@code cash_day_store_open_ix} (V8006). */
    Optional<CashDay> findFirstByStoreIdAndStatusOrderByBusinessDateAsc(UUID storeId, CashDayStatus status);

    List<CashDay> findByStoreIdAndBusinessDateBetweenOrderByBusinessDateAsc(UUID storeId, LocalDate from,
                                                                            LocalDate to);

    /** P28: a session never opens in a day earlier than one already opened. */
    Optional<CashDay> findFirstByStoreIdAndBusinessDateAfter(UUID storeId, LocalDate businessDate);

    /** With {@code OPEN}: an earlier day still to close (P28 warning); served by {@code cash_day_store_open_ix}. */
    boolean existsByStoreIdAndStatusAndBusinessDateBefore(UUID storeId, CashDayStatus status, LocalDate businessDate);

    @Query("select d.storeId from CashDay d where d.id = :id")
    Optional<UUID> findStoreIdById(@Param("id") UUID id);
}
