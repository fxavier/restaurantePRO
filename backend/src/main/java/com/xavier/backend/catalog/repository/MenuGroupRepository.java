package com.xavier.backend.catalog.repository;

import com.xavier.backend.catalog.model.MenuGroup;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MenuGroupRepository extends JpaRepository<MenuGroup, UUID> {

    List<MenuGroup> findAllByItemIdOrderByPositionAscCodeAsc(UUID itemId);

    List<MenuGroup> findAllByItemIdIn(Collection<UUID> itemIds);

    Optional<MenuGroup> findByIdAndItemId(UUID id, UUID itemId);
}
