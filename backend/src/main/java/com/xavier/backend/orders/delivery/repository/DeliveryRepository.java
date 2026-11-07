package com.xavier.backend.orders.delivery.repository;

import com.xavier.backend.orders.DeliveryStatus;
import com.xavier.backend.orders.delivery.model.Delivery;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeliveryRepository extends JpaRepository<Delivery, UUID> {

    /** D42 (b): only the store of a delivery addressed by its order id, before authorizing. */
    @Query("select d.storeId from Delivery d where d.orderId = :orderId")
    Optional<UUID> findStoreIdByOrderId(@Param("orderId") UUID orderId);

    List<Delivery> findByStoreIdAndStatusInOrderByOrderId(UUID storeId, Collection<DeliveryStatus> statuses);

    /** The store's deliveries whose order's business day is in {@code [from, to]}, optionally of one courier. */
    @Query("""
            select d from Delivery d where d.storeId = :storeId
                and (:courierId is null or d.courierEmployeeId = :courierId)
                and d.orderId in (select o.id from Order o where o.storeId = :storeId
                                  and o.businessDay between :from and :to)""")
    Page<Delivery> findInDays(@Param("storeId") UUID storeId, @Param("from") LocalDate from,
                              @Param("to") LocalDate to, @Param("courierId") UUID courierId, Pageable pageable);
}
