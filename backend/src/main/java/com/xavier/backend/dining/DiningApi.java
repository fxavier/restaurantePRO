package com.xavier.backend.dining;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.dining.exception.DiningNotFoundException;
import com.xavier.backend.dining.exception.IllegalOccupationStateException;
import com.xavier.backend.dining.exception.TableOccupiedException;
import com.xavier.backend.dining.model.RestaurantTable;
import com.xavier.backend.dining.model.RoomArea;
import com.xavier.backend.dining.model.TableOccupation;
import com.xavier.backend.dining.model.dto.CloseReason;
import com.xavier.backend.dining.model.dto.OccupationView;
import com.xavier.backend.dining.model.dto.TableState;
import com.xavier.backend.dining.repository.RestaurantTableRepository;
import com.xavier.backend.dining.repository.RoomAreaRepository;
import com.xavier.backend.dining.repository.TableOccupationRepository;
import com.xavier.backend.dining.service.DiningChanges;
import com.xavier.backend.dining.service.DiningRows;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What orders (S13) asks the dining module about table occupations (D36, r2-decisions §4 dining). Writes run in
 * the caller's transaction ({@code MANDATORY}), so an order and its occupation commit or roll back together. It
 * does not authorize: the calling POS use case already did (D27). The session store (D39) is the store a table
 * must belong to. Every change is audited as the current actor (D41) and notifies {@code TABLES} after commit.
 *
 * <p>Writes need an authenticated session actor in the security context: the session's store is the store a new
 * occupation must be in, and the audit records that actor. A call from a listener or job (no session) fails.
 */
@Service
public class DiningApi {

    static final String ENTITY = "TableOccupation";

    private final TableOccupationRepository occupations;
    private final RestaurantTableRepository tables;
    private final RoomAreaRepository areas;
    private final AccessPolicy accessPolicy;
    private final DiningChanges changes;
    private final DiningRows rows;
    private final Clock clock;

    DiningApi(TableOccupationRepository occupations, RestaurantTableRepository tables, RoomAreaRepository areas,
              AccessPolicy accessPolicy, DiningChanges changes, DiningRows rows, Clock clock) {
        this.occupations = occupations;
        this.tables = tables;
        this.areas = areas;
        this.accessPolicy = accessPolicy;
        this.changes = changes;
        this.rows = rows;
        this.clock = clock;
    }

    /**
     * Seats {@code orderId} at {@code tableId}.
     *
     * @throws TableOccupiedException 409 {@code TABLE_OCCUPIED} (also when a concurrent opening won), or
     *         {@code ORDER_SEATED} when the order already occupies a table
     * @throws com.xavier.backend.dining.exception.TableUnavailableException 422 {@code STORE_MISMATCH} (not the
     *         session's store), {@code TABLE_INACTIVE} (table or area), {@code TABLE_LOCKED}
     * @throws DiningNotFoundException 404 for an unknown table
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OccupationView openOccupation(UUID tableId, UUID orderId, int guests, UUID employeeId) {
        RestaurantTable table = lockedOpenableTable(tableId, accessPolicy.current().storeId());
        TableOccupation occupation = save(TableOccupation.open(table.getStoreId(), tableId, orderId, guests,
                employeeId, clock.instant()));
        changed("TABLE_OCCUPATION_OPENED", occupation, Map.of("guests", guests));
        return occupation.toView(table.getCode());
    }

    /**
     * Frees the order's table when the order closes or is cancelled.
     *
     * @param reason {@code ORDER_CLOSED} or {@code ORDER_CANCELLED}; {@code MOVED} belongs to {@link #moveOccupation}
     * @throws com.xavier.backend.dining.exception.IllegalOccupationStateException 409 {@code NO_OPEN_OCCUPATION}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void closeOccupation(UUID orderId, CloseReason reason) {
        if (reason == CloseReason.MOVED) {
            throw new IllegalArgumentException("A move closes an occupation through moveOccupation");
        }
        TableOccupation occupation = openOccupationOf(orderId);
        occupation.close(reason, clock.instant());
        occupations.saveAndFlush(occupation);
        changed("TABLE_OCCUPATION_CLOSED", occupation, Map.of("reason", reason.name()));
    }

    /**
     * Moves the order's party to {@code toTableId}: the current occupation closes as {@code MOVED} and the target gets
     * a new one with the same seating time, opening employee, guests and subtotal mark.
     *
     * @throws TableOccupiedException 409 {@code TABLE_OCCUPIED} (also for the order's own table)
     * @throws com.xavier.backend.dining.exception.TableUnavailableException 422, as for {@link #openOccupation};
     *         {@code STORE_MISMATCH} when the target is in another store than the occupation
     * @throws com.xavier.backend.dining.exception.IllegalOccupationStateException 409 {@code NO_OPEN_OCCUPATION}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OccupationView moveOccupation(UUID orderId, UUID toTableId) {
        TableOccupation from = openOccupationOf(orderId);
        RestaurantTable target = lockedOpenableTable(toTableId, from.getStoreId());
        TableOccupation to = from.moveTo(toTableId, clock.instant());
        occupations.saveAndFlush(from); // close first: the open-order index allows one open occupation per order
        save(to);
        changed("TABLE_OCCUPATION_CLOSED", from, Map.of("reason", CloseReason.MOVED.name()));
        changed("TABLE_OCCUPATION_MOVED", to, Map.of("fromTableId", from.getTableId().toString(),
                "fromOccupationId", from.getId().toString()));
        return to.toView(target.getCode());
    }

    /** Marks that the order's subtotal (a non-fiscal bill) was printed, a table map indicator (spec §8.4). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markSubtotalPrinted(UUID orderId) {
        TableOccupation occupation = openOccupationOf(orderId);
        occupation.markSubtotalPrinted(clock.instant());
        occupations.saveAndFlush(occupation);
        changed("TABLE_SUBTOTAL_PRINTED", occupation, Map.of());
    }

    @Transactional(readOnly = true)
    public TableState tableState(UUID tableId) {
        RestaurantTable table = table(tableId);
        UUID openOrderId = occupations.findByTableIdAndClosedAtIsNull(tableId)
                .map(TableOccupation::getOrderId).orElse(null);
        return new TableState(tableId, table.getStoreId(), table.isActive(), area(table).isActive(), table.isLocked(),
                openOrderId);
    }

    /**
     * Locks and re-reads the table row, then the area row (always in that order), so a concurrent opening, lock or
     * deactivation of the same table or area waits for this transaction and then sees its outcome, also when the
     * caller read the table earlier in its transaction.
     */
    private RestaurantTable lockedOpenableTable(UUID tableId, UUID storeId) {
        RestaurantTable table = rows.lockTable(tableId);
        table.requireOpenable(storeId, rows.shareArea(table.getAreaId()));
        if (occupations.findByTableIdAndClosedAtIsNull(tableId).isPresent()) {
            throw TableOccupiedException.table(tableId);
        }
        return table;
    }

    /** The partial unique indexes decide a race the checks missed: 409, never 500. */
    private TableOccupation save(TableOccupation occupation) {
        try {
            return occupations.saveAndFlush(occupation);
        } catch (DataIntegrityViolationException e) {
            if (UniqueConstraints.isViolated(e, "table_occupation_open_table_uk")) {
                throw TableOccupiedException.table(occupation.getTableId());
            }
            if (UniqueConstraints.isViolated(e, "table_occupation_open_order_uk")) {
                throw TableOccupiedException.order(occupation.getOrderId());
            }
            throw e;
        }
    }

    private TableOccupation openOccupationOf(UUID orderId) {
        return occupations.findByOrderIdAndClosedAtIsNull(orderId)
                .orElseThrow(() -> IllegalOccupationStateException.noOpenOccupation(orderId));
    }

    private void changed(String action, TableOccupation occupation, Map<String, Object> extra) {
        Map<String, Object> details = new HashMap<>(extra);
        details.put("tableId", occupation.getTableId().toString());
        details.put("orderId", occupation.getOrderId().toString());
        changes.record(action, ENTITY, occupation.getId(), occupation.getStoreId(), occupation.getTableId(), details);
    }

    private RestaurantTable table(UUID tableId) {
        return tables.findById(tableId).orElseThrow(() -> new DiningNotFoundException("RestaurantTable", tableId));
    }

    private RoomArea area(RestaurantTable table) {
        return areas.findById(table.getAreaId()).orElseThrow();
    }
}
