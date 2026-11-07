package com.xavier.backend.catalog.repository;

import com.xavier.backend.catalog.model.SoldOutFlag;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SoldOutFlagRepository extends JpaRepository<SoldOutFlag, UUID> {

    List<SoldOutFlag> findAllByStoreIdOrderBySoldOutAtAscIdAsc(UUID storeId);

    /** A {@code null} {@code variantId} matches the item-level flag only. */
    Optional<SoldOutFlag> findByStoreIdAndItemIdAndVariantId(UUID storeId, UUID itemId, UUID variantId);

    /** @return 1 when inserted, 0 when the key is already flagged (also by a concurrent request) */
    @Modifying
    @NativeQuery("""
            INSERT INTO catalog.item_availability (store_id, item_id, variant_id, sold_out_at, sold_out_by_actor_kind,
                                                   sold_out_by_actor_id, reason)
            VALUES (:storeId, :itemId, CAST(:variantId AS uuid), :at, :actorKind, :actorId, CAST(:reason AS varchar))
            ON CONFLICT DO NOTHING""")
    int insertIfAbsent(@Param("storeId") UUID storeId, @Param("itemId") UUID itemId,
                       @Param("variantId") UUID variantId, @Param("at") Instant at,
                       @Param("actorKind") String actorKind, @Param("actorId") UUID actorId,
                       @Param("reason") String reason);

    /** @return 1 when removed, 0 when a concurrent request removed it first */
    @Modifying
    @Query("DELETE FROM SoldOutFlag f WHERE f.id = :id")
    int remove(@Param("id") UUID id);
}
