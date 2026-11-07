package com.xavier.backend.orders.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.catalog.CatalogApi;
import com.xavier.backend.shared.exception.NotFoundException;
import com.xavier.backend.catalog.model.dto.CatalogSnapshot;
import java.util.Optional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import com.xavier.backend.dining.DiningApi;
import com.xavier.backend.dining.model.dto.OccupationView;
import com.xavier.backend.organization.BusinessDays;
import com.xavier.backend.organization.OrganizationApi;
import com.xavier.backend.organization.model.dto.StoreSummary;
import com.xavier.backend.orders.exception.OrderConflictException;
import com.xavier.backend.orders.model.Order;
import com.xavier.backend.orders.model.dto.OpenOrderRequest;
import com.xavier.backend.orders.model.dto.OpenTableRequest;
import com.xavier.backend.orders.model.dto.OrderView;
import com.xavier.backend.orders.repository.OrderRepository;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opens orders (P15, A1). A DINE_IN order and its table occupation are created in one transaction: the order's
 * partial unique index and dining's occupation index each let one of two concurrent openings win (409
 * {@code TABLE_OCCUPIED}); dining refuses inactive, locked and other-store tables (422) and the order rolls back with
 * it. The business day is fixed at opening (D16).
 */
@Service
public class OrderOpeningService {

    static final String OPEN_TABLE_UK = "order_header_open_table_uk";

    private final OrderRepository orders;
    private final OrderLookup lookup;
    private final DiningApi dining;
    private final OrganizationApi organization;
    private final CatalogApi catalog;
    private final DraftLineService drafts;
    private final TransactionTemplate ownReadOnly;
    private final AccessPolicy accessPolicy;
    private final OrderChanges changes;
    private final OrderViews views;
    private final Clock clock;

    OrderOpeningService(OrderRepository orders, OrderLookup lookup, DiningApi dining, OrganizationApi organization,
                        CatalogApi catalog, DraftLineService drafts, AccessPolicy accessPolicy,
                        OrderChanges changes, OrderViews views, PlatformTransactionManager transactionManager,
                        Clock clock) {
        this.catalog = catalog;
        this.drafts = drafts;
        this.ownReadOnly = new TransactionTemplate(transactionManager);
        this.ownReadOnly.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.ownReadOnly.setReadOnly(true);
        this.orders = orders;
        this.lookup = lookup;
        this.dining = dining;
        this.organization = organization;
        this.accessPolicy = accessPolicy;
        this.changes = changes;
        this.views = views;
        this.clock = clock;
    }

    @Transactional
    public OrderView openTable(UUID tableId, OpenTableRequest request) {
        PosContext pos = accessPolicy.requirePos(Permission.ORDER_EDIT);
        lookup.requireSeatable(tableId, pos);
        StoreSummary store = store(pos);
        Instant now = clock.instant();
        Order order = Order.dineIn(store.id(), pos.terminalId(), orders.nextOrderNumber(), tableId,
                request.guests(), BusinessDays.of(now, store.timezone(), store.businessDayCutoff()), store.currency(),
                pos.employeeId(), now);
        UniqueConstraints.saveAndFlush(orders, order, () -> OrderConflictException.tableOccupied(tableId),
                OPEN_TABLE_UK);
        OccupationView occupation = dining.openOccupation(tableId, order.getId(), request.guests(), pos.employeeId());
        order.seatedAt(tableId, occupation.tableCode());
        orders.saveAndFlush(order);
        opened(order, pos);
        return views.of(order);
    }

    @Transactional
    public OrderView openWalkIn(OpenOrderRequest request) {
        PosContext pos = accessPolicy.requirePos(Permission.ORDER_EDIT);
        StoreSummary store = store(pos);
        Instant now = clock.instant();
        Order order = orders.saveAndFlush(Order.walkIn(request.mode(), store.id(), pos.terminalId(),
                orders.nextOrderNumber(), request.guests(),
                BusinessDays.of(now, store.timezone(), store.businessDayCutoff()), store.currency(),
                pos.employeeId(), now));
        opened(order, pos);
        return views.of(order);
    }

    /** Audits the opening, then adds the automatic drafts of the published catalog (D109). */
    private void opened(Order order, PosContext pos) {
        Map<String, Object> details = new HashMap<>();
        details.put("mode", order.getMode().name());
        details.put("orderNumber", order.getOrderNumber());
        if (order.getTableId() != null) {
            details.put("tableId", order.getTableId().toString());
        }
        changes.record("ORDER_OPENED", order, details);
        publishedCatalog(order.getStoreId())
                .ifPresent(current -> drafts.addAutomatic(order, current, pos.employeeId()));
    }

    /**
     * The store's published catalog, or empty when it has none (an order still opens, as in R1–R3). Read in its own
     * read-only transaction: the catalog's 404 would otherwise mark the opening's transaction rollback-only.
     */
    private Optional<CatalogSnapshot> publishedCatalog(UUID storeId) {
        try {
            return Optional.ofNullable(ownReadOnly.execute(status -> catalog.currentSnapshot(storeId)));
        } catch (NotFoundException noPublishedCatalog) {
            return Optional.empty();
        }
    }

    private StoreSummary store(PosContext pos) {
        return organization.findStore(pos.storeId())
                .orElseThrow(() -> new IllegalStateException("requirePos let an unknown store through: " + pos));
    }
}
