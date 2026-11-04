package com.xavier.backend.catalog.repository;

import com.xavier.backend.catalog.model.TaxCategory;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaxCategoryRepository extends JpaRepository<TaxCategory, UUID> {
}
