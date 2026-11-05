package com.xavier.backend.customers.repository;

import com.xavier.backend.customers.model.AddressKind;
import com.xavier.backend.customers.model.CustomerAddress;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerAddressRepository extends JpaRepository<CustomerAddress, UUID> {

    List<CustomerAddress> findByCustomerIdOrderByIdAsc(UUID customerId);

    Optional<CustomerAddress> findFirstByCustomerIdAndKindOrderByIdAsc(UUID customerId, AddressKind kind);
}
