package com.xavier.backend.inventory.preparation.repository;

import com.xavier.backend.inventory.preparation.model.Preparation;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface PreparationRepository extends JpaRepository<Preparation, UUID> {

    @Query("SELECT p.storeId FROM Preparation p WHERE p.id = :id")
    Optional<UUID> storeOf(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Preparation p WHERE p.id = :id")
    Optional<Preparation> lockById(UUID id);
}
