package com.xavier.backend.customers.repository;

import com.xavier.backend.customers.model.CreditAccount;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CreditAccountRepository extends JpaRepository<CreditAccount, UUID> {
}
