package com.xavier.backend.customers.repository;

import com.xavier.backend.customers.model.CustomerPhone;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerPhoneRepository extends JpaRepository<CustomerPhone, UUID> {

    List<CustomerPhone> findByCustomerIdInOrderByPrimaryDescDigitsAsc(Collection<UUID> customerIds);

    List<CustomerPhone> findByCustomerIdOrderByPrimaryDescDigitsAsc(UUID customerId);
}
