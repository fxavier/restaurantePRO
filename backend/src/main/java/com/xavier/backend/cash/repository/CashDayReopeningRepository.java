package com.xavier.backend.cash.repository;

import com.xavier.backend.cash.model.CashDayReopening;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CashDayReopeningRepository extends JpaRepository<CashDayReopening, UUID> {
}
