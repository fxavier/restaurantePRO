package com.xavier.backend.promotions.repository;

import com.xavier.backend.promotions.model.StoreDiscountLimits;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreDiscountLimitsRepository extends JpaRepository<StoreDiscountLimits, UUID> {
}
