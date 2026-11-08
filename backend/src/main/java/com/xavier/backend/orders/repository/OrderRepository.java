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
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    /** D42 (b): only the store of an order addressed by id, before authorizing. */
    @Query("select o.storeId from Order o where o.id = :id")
    Optional<UUID> findStoreIdById(@Param("id") UUID id);

    /**
     * Moves the order's version on as one compare-and-set, for a change that writes facts but no order column (a void,
     * a transfer, D49). The row stays locked until commit, so a concurrent change of the same order waits here and
     * then finds another version.
     *
     * @return 1 when the order was at {@code version}, 0 when it was not (stale, 409)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Order o set o.version = o.version + 1 where o.id = :id and o.version = :version")
    int incrementVersion(@Param("id") UUID id, @Param("version") long version);

    /**
     * A delivery dispatch's lock on the order (D132: before the delivery row), until commit, without moving its
     * version. {@code FOR UPDATE} conflicts with the {@code FOR KEY SHARE} a new draft's foreign key takes, so the
     * dispatch waits for a draft being added and then reads it.
     */
    @Query(value = "SELECT id FROM orders.order_header WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<UUID> lockForDispatch(@Param("id") UUID id);

    @Query(value = "SELECT nextval('orders.order_number_seq')", nativeQuery = true)
    long nextOrderNumber();

    Page<Order> findByStoreIdAndLifecycle(UUID storeId, OrderLifecycle lifecycle, Pageable pageable);

    List<Order> findByStoreIdAndLifecycleOrderByOpenedAt(UUID storeId, OrderLifecycle lifecycle);

    long countByStoreIdAndLifecycleAndBusinessDayLessThanEqual(UUID storeId, OrderLifecycle lifecycle,
                                                              LocalDate businessDay);
}
