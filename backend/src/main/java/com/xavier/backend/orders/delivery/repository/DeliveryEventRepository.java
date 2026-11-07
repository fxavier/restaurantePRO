package com.xavier.backend.orders.delivery.repository;

import com.xavier.backend.orders.delivery.model.DeliveryEvent;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeliveryEventRepository extends JpaRepository<DeliveryEvent, UUID> {

    /** Ids are UUIDv7: id order is the order they were written in. */
    List<DeliveryEvent> findByOrderIdInOrderById(Collection<UUID> orderIds);
}
