package com.xavier.backend.customers.repository;

import com.xavier.backend.customers.model.CreditSettlement;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CreditSettlementRepository extends JpaRepository<CreditSettlement, UUID> {
}
