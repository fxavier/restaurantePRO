package com.xavier.backend.orders.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.dining.DiningApi;
import com.xavier.backend.dining.model.dto.OccupationView;
import com.xavier.backend.orders.exception.OrderConflictException;
import com.xavier.backend.orders.model.Order;
import com.xavier.backend.orders.model.dto.OrderView;
import com.xavier.backend.orders.model.dto.TableMoveRequest;
import com.xavier.backend.orders.repository.OrderRepository;
import com.xavier.backend.shared.persistence.OptimisticLocking;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves a DINE_IN order to another table (spec §4; r2-decisions §4 dining) through {@code DiningApi.moveOccupation}.
 *
 * <p><b>Lock order</b>, the same as a table opening's ({@link OrderOpeningService}): first the order row and its
 * open-table index entry for the target ({@code order_header_open_table_uk}, written by flushing the new
 * {@code table_id}), then dining's rows (the order's occupation and the target table, {@code FOR UPDATE}). An opening
 * inserts its order (the same index entry) before dining locks the table, so a move and an opening of one table queue
 * on the index entry and never hold each other's next lock: one wins, the other gets 409 {@code TABLE_OCCUPIED}.
 * The table code arrives from dining after its rows are locked and is written in the same transaction; it is excluded
 * from optimistic locking, so table and code always commit together.
 */
@Service
public class TableMoveService {

    private final OrderRepository orders;
    private final OrderLookup lookup;
    private final DiningApi dining;
    private final AccessPolicy accessPolicy;
    private final OrderChanges changes;
    private final OrderViews views;

    TableMoveService(OrderRepository orders, OrderLookup lookup, DiningApi dining, AccessPolicy accessPolicy,
                     OrderChanges changes, OrderViews views) {
        this.orders = orders;
        this.lookup = lookup;
        this.dining = dining;
        this.accessPolicy = accessPolicy;
        this.changes = changes;
        this.views = views;
    }

    /**
     * @throws OrderConflictException 409 {@code TABLE_OCCUPIED} (also the order's own table)
     * @throws com.xavier.backend.orders.exception.OrderRuleException 422 {@code ORDER_HAS_NO_TABLE},
     *         {@code ORDER_FROZEN}, {@code STORE_MISMATCH}, {@code TABLE_INACTIVE}, {@code TABLE_LOCKED}
     */
    @Transactional
    public OrderView move(UUID orderId, TableMoveRequest request) {
        PosContext pos = accessPolicy.requirePos(Permission.ORDER_TRANSFER);
        Order order = lookup.inSessionStore(pos, orderId);
        OptimisticLocking.requireVersion(Order.class, orderId, order.getVersion(), request.orderVersion());
        UUID from = order.getTableId();
        UUID to = request.tableId();
        order.moveTo(to);
        lookup.requireSeatable(to, pos);
        UniqueConstraints.saveAndFlush(orders, order, () -> OrderConflictException.tableOccupied(to),
                OrderOpeningService.OPEN_TABLE_UK);
        OccupationView occupation = dining.moveOccupation(orderId, to);
        order.seatedAt(to, occupation.tableCode());
        orders.saveAndFlush(order);
        changes.record("ORDER_TABLE_MOVED", order, Map.of("fromTableId", from.toString(), "toTableId", to.toString(),
                "tableCode", occupation.tableCode()));
        return views.of(order);
    }
}
