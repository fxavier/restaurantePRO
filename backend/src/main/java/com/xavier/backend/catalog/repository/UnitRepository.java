package com.xavier.backend.catalog.repository;

import com.xavier.backend.catalog.model.Unit;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UnitRepository extends JpaRepository<Unit, UUID> {
}
