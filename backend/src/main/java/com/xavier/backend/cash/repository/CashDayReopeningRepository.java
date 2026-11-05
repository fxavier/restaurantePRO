package com.xavier.backend.cash.repository;

import com.xavier.backend.cash.model.CashDayReopening;
import com.xavier.backend.cash.model.CashDayStatus;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CashDayReopeningRepository extends JpaRepository<CashDayReopening, UUID> {

    /** With {@code FINANCIALLY_CLOSED}: the day's round (D79 (3)). */
    long countByCashDayIdAndFromStatus(UUID cashDayId, CashDayStatus fromStatus);

    boolean existsByCashDayId(UUID cashDayId);
}
