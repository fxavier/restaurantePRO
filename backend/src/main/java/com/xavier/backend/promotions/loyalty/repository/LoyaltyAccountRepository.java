package com.xavier.backend.promotions.loyalty.repository;

import com.xavier.backend.promotions.loyalty.model.LoyaltyAccount;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoyaltyAccountRepository extends JpaRepository<LoyaltyAccount, UUID> {
}
