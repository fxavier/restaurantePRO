package com.xavier.backend.settlement.repository;

import com.xavier.backend.settlement.model.PaymentAllocation;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentAllocationRepository extends JpaRepository<PaymentAllocation, UUID> {
}
