package com.xavier.backend.customers.repository;

import com.xavier.backend.customers.model.CreditLimitChange;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CreditLimitChangeRepository extends JpaRepository<CreditLimitChange, UUID> {
}
