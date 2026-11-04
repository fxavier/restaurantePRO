package com.xavier.backend.orders.repository;

import com.xavier.backend.orders.OrderLifecycle;
import com.xavier.backend.orders.model.Order;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    /** D42 (b): only the store of an order addressed by id, before authorizing. */
    @Query("select o.storeId from Order o where o.id = :id")
    Optional<UUID> findStoreIdById(@Param("id") UUID id);

    @Query(value = "SELECT nextval('orders.order_number_seq')", nativeQuery = true)
    long nextOrderNumber();

    Page<Order> findByStoreIdAndLifecycle(UUID storeId, OrderLifecycle lifecycle, Pageable pageable);

    List<Order> findByStoreIdAndLifecycleOrderByOpenedAt(UUID storeId, OrderLifecycle lifecycle);

    long countByStoreIdAndLifecycleAndBusinessDayLessThanEqual(UUID storeId, OrderLifecycle lifecycle,
                                                              LocalDate businessDay);
}
