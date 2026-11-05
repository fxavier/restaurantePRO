package com.xavier.backend.orders.repository;

import com.xavier.backend.orders.model.OrderLineVoid;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderLineVoidRepository extends JpaRepository<OrderLineVoid, UUID> {

    /** The store's voids whose order's business day is in {@code [from, to]}, in id (creation) order. */
    @Query(value = """
            select v.id from OrderLineVoid v, Order o
            where o.id = v.orderId and o.storeId = :storeId and o.businessDay between :from and :to
            order by v.id""",
            countQuery = """
            select count(v) from OrderLineVoid v, Order o
            where o.id = v.orderId and o.storeId = :storeId and o.businessDay between :from and :to""")
    Page<UUID> findIdsByStoreAndBusinessDay(@Param("storeId") UUID storeId, @Param("from") LocalDate from,
                                            @Param("to") LocalDate to, Pageable pageable);

    /**
     * Whether production had received the voided line's round when the void was recorded. A line transferred in has
     * no round of its own: its origin is followed back through the transfer facts to the round it was confirmed in.
     */
    @Query(value = """
            WITH RECURSIVE origin (line_id, batch_id, voided_at) AS (
                SELECT l.id, l.batch_id, v.voided_at
                FROM orders.order_line_void v JOIN orders.order_line l ON l.id = v.line_id
                WHERE v.id = :voidId
                UNION ALL
                SELECT s.id, s.batch_id, o.voided_at
                FROM origin o
                JOIN orders.order_transfer_line t ON t.target_line_id = o.line_id
                JOIN orders.order_line s ON s.id = t.source_line_id
                WHERE o.batch_id IS NULL)
            SELECT EXISTS (SELECT 1 FROM origin o JOIN orders.order_batch b ON b.id = o.batch_id
                           WHERE b.dispatched_at <= o.voided_at)""", nativeQuery = true)
    boolean wasAfterDispatch(@Param("voidId") UUID voidId);
}
