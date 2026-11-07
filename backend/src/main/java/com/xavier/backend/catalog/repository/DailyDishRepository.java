package com.xavier.backend.catalog.repository;

import com.xavier.backend.catalog.model.DailyDish;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.repository.query.Param;

public interface DailyDishRepository extends JpaRepository<DailyDish, DailyDish.Key> {

    List<DailyDish> findAllByIdStoreIdAndIdBusinessDateOrderByPosition(UUID storeId, LocalDate businessDate);

    /** Serializes the replacements of one store's day until the transaction ends (transaction-level advisory lock). */
    @NativeQuery("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended('catalog.daily_dish:' || :storeId || ':' || :day, 0))")
    Integer lockDay(@Param("storeId") String storeId, @Param("day") String day);

    @Modifying
    @NativeQuery("DELETE FROM catalog.daily_dish WHERE store_id = :storeId AND business_date = :day")
    int clear(@Param("storeId") UUID storeId, @Param("day") LocalDate day);

    @Modifying
    @NativeQuery("""
            INSERT INTO catalog.daily_dish (store_id, business_date, item_id, position)
            VALUES (:storeId, :day, :itemId, :position)""")
    int add(@Param("storeId") UUID storeId, @Param("day") LocalDate day, @Param("itemId") UUID itemId,
            @Param("position") int position);
}
