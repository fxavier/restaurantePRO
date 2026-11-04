package com.xavier.backend.settlement.repository;

import com.xavier.backend.settlement.model.PaymentMethod;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentMethodRepository extends JpaRepository<PaymentMethod, UUID> {

    Optional<PaymentMethod> findByCode(String code);

    List<PaymentMethod> findAllByOrderByCode();
}
