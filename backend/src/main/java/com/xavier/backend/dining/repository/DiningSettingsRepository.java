package com.xavier.backend.dining.repository;

import com.xavier.backend.dining.model.DiningSettings;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiningSettingsRepository extends JpaRepository<DiningSettings, UUID> {
}
