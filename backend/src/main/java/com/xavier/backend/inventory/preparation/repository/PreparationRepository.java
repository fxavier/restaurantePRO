package com.xavier.backend.inventory.preparation.repository;

import com.xavier.backend.inventory.preparation.model.Preparation;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface PreparationRepository extends JpaRepository<Preparation, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Preparation p WHERE p.id = :id")
    Optional<Preparation> lockById(UUID id);
}
