package com.xavier.backend.customers.repository;

import com.xavier.backend.customers.model.CreditReservation;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CreditReservationRepository extends JpaRepository<CreditReservation, UUID> {

    Optional<CreditReservation> findByChargeId(UUID chargeId);

    /** Served by the partial index {@code credit_reservation_active_ix}. */
    @Query(nativeQuery = true, value = """
            SELECT coalesce(sum(amount), 0) FROM customers.credit_reservation
            WHERE customer_id = :customerId AND status = 'ACTIVE'""")
    BigDecimal sumActive(@Param("customerId") UUID customerId);
}
