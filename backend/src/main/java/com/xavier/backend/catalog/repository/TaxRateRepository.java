package com.xavier.backend.catalog.repository;

import com.xavier.backend.catalog.model.TaxRate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaxRateRepository extends JpaRepository<TaxRate, UUID> {

    List<TaxRate> findAllByTaxCategoryIdOrderByEffectiveFrom(UUID taxCategoryId);

    List<TaxRate> findAllByTaxCategoryIdIn(Collection<UUID> taxCategoryIds);
}
