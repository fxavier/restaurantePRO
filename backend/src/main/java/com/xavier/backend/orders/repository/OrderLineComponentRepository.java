package com.xavier.backend.orders.repository;

import com.xavier.backend.orders.model.OrderLineComponent;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderLineComponentRepository extends JpaRepository<OrderLineComponent, UUID> {

    List<OrderLineComponent> findByLineIdInOrderByLineIdAscPositionAsc(Collection<UUID> lineIds);

    List<OrderLineComponent> findByOrderIdOrderByLineIdAscPositionAsc(UUID orderId);

    List<OrderLineComponent> findByLineIdOrderByPosition(UUID lineId);
}
