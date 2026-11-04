package com.xavier.backend.catalog.repository;

import com.xavier.backend.catalog.model.Family;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FamilyRepository extends JpaRepository<Family, UUID> {

    boolean existsByParentId(UUID parentId);
}
