package com.xavier.backend.catalog.repository;

import com.xavier.backend.catalog.model.ItemVariant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItemVariantRepository extends JpaRepository<ItemVariant, UUID> {

    List<ItemVariant> findAllByItemIdOrderByCode(UUID itemId);

    List<ItemVariant> findAllByItemIdIn(Collection<UUID> itemIds);

    Optional<ItemVariant> findByIdAndItemId(UUID id, UUID itemId);

    boolean existsByIdAndItemId(UUID id, UUID itemId);
}
