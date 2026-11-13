package com.xavier.backend.catalog.repository;

import com.xavier.backend.catalog.model.StoreItemExclusion;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StoreItemExclusionRepository extends JpaRepository<StoreItemExclusion, StoreItemExclusion.Key> {

    List<StoreItemExclusion> findAllByIdStoreId(UUID storeId);

    Page<StoreItemExclusion> findAllByIdStoreId(UUID storeId, Pageable pageable);

    Optional<StoreItemExclusion> findByIdStoreIdAndIdItemId(UUID storeId, UUID itemId);

    /** @return 1 when inserted, 0 when the item is already excluded (also by a concurrent request) */
    @Modifying
    @NativeQuery("""
            INSERT INTO catalog.store_item_exclusion (store_id, item_id, reason, excluded_at, excluded_by_account_id)
            VALUES (:storeId, :itemId, CAST(:reason AS varchar), :at, :accountId)
            ON CONFLICT DO NOTHING""")
    int insertIfAbsent(@Param("storeId") UUID storeId, @Param("itemId") UUID itemId, @Param("reason") String reason,
                       @Param("at") Instant at, @Param("accountId") UUID accountId);

    /** @return 1 when the reason changed, 0 when it already was {@code reason} or the row is gone */
    @Modifying(clearAutomatically = true)
    @NativeQuery("""
            UPDATE catalog.store_item_exclusion SET reason = CAST(:reason AS varchar)
            WHERE store_id = :storeId AND item_id = :itemId AND reason IS DISTINCT FROM CAST(:reason AS varchar)""")
    int changeReason(@Param("storeId") UUID storeId, @Param("itemId") UUID itemId, @Param("reason") String reason);

    /** @return 1 when removed, 0 when the item was not excluded (or a concurrent request removed it first) */
    @Modifying
    @Query("DELETE FROM StoreItemExclusion e WHERE e.id.storeId = :storeId AND e.id.itemId = :itemId")
    int remove(@Param("storeId") UUID storeId, @Param("itemId") UUID itemId);
}
