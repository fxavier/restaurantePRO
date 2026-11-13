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

    /**
     * Excludes the item, or sets the reason of its exclusion, in one statement. The conflicting row is locked even when
     * the reason is unchanged, so a concurrent DELETE waits for this transaction.
     *
     * @return 1 when inserted or the reason changed, 0 when the exclusion already had {@code reason}
     */
    @Modifying(clearAutomatically = true)
    @NativeQuery("""
            INSERT INTO catalog.store_item_exclusion (store_id, item_id, reason, excluded_at, excluded_by_account_id)
            VALUES (:storeId, :itemId, CAST(:reason AS varchar), :at, :accountId)
            ON CONFLICT (store_id, item_id) DO UPDATE SET reason = EXCLUDED.reason
            WHERE store_item_exclusion.reason IS DISTINCT FROM EXCLUDED.reason""")
    int upsert(@Param("storeId") UUID storeId, @Param("itemId") UUID itemId, @Param("reason") String reason,
               @Param("at") Instant at, @Param("accountId") UUID accountId);

    /** @return 1 when removed, 0 when the item was not excluded (or a concurrent request removed it first) */
    @Modifying
    @Query("DELETE FROM StoreItemExclusion e WHERE e.id.storeId = :storeId AND e.id.itemId = :itemId")
    int remove(@Param("storeId") UUID storeId, @Param("itemId") UUID itemId);
}
