package com.xavier.backend.dining;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.dining.exception.DiningNotFoundException;
import com.xavier.backend.dining.exception.TableOccupiedException;
import com.xavier.backend.dining.model.RestaurantTable;
import com.xavier.backend.dining.model.RoomArea;
import com.xavier.backend.dining.model.TableOccupation;
import com.xavier.backend.dining.model.dto.OccupationView;
import com.xavier.backend.dining.model.dto.TableState;
import com.xavier.backend.dining.repository.RestaurantTableRepository;
import com.xavier.backend.dining.repository.RoomAreaRepository;
import com.xavier.backend.dining.repository.TableOccupationRepository;
import com.xavier.backend.shared.notify.ChangeNotifier;
import com.xavier.backend.shared.notify.ChangeTopic;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import java.time.Clock;
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
 */
@Service
public class DiningApi {

    static final String ENTITY = "TableOccupation";

    private final TableOccupationRepository occupations;
    private final RestaurantTableRepository tables;
    private final RoomAreaRepository areas;
    private final AccessPolicy accessPolicy;
    private final AuditLog auditLog;
    private final ChangeNotifier changeNotifier;
    private final Clock clock;

    DiningApi(TableOccupationRepository occupations, RestaurantTableRepository tables, RoomAreaRepository areas,
              AccessPolicy accessPolicy, AuditLog auditLog, ChangeNotifier changeNotifier, Clock clock) {
        this.occupations = occupations;
        this.tables = tables;
        this.areas = areas;
        this.accessPolicy = accessPolicy;
        this.auditLog = auditLog;
        this.changeNotifier = changeNotifier;
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
        changed("TABLE_OCCUPATION_OPENED", occupation);
        return occupation.toView();
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
     * Locks the table row, then shares the area row (always in that order), so a concurrent opening, lock or
     * deactivation of the same table or area waits for this transaction and then sees its outcome.
     */
    private RestaurantTable lockedOpenableTable(UUID tableId, UUID storeId) {
        RestaurantTable table = tables.findForUpdateById(tableId)
                .orElseThrow(() -> new DiningNotFoundException("RestaurantTable", tableId));
        table.requireOpenable(storeId, areas.findForShareById(table.getAreaId()).orElseThrow());
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

    private void changed(String action, TableOccupation occupation) {
        auditLog.recordAsCurrentActor(action, ENTITY, occupation.getId().toString(), occupation.getStoreId(), null,
                null, Map.of("tableId", occupation.getTableId().toString(),
                        "orderId", occupation.getOrderId().toString()));
        changeNotifier.notifyAfterCommit(occupation.getStoreId(), ChangeTopic.TABLES, occupation.getTableId());
    }

    private RestaurantTable table(UUID tableId) {
        return tables.findById(tableId).orElseThrow(() -> new DiningNotFoundException("RestaurantTable", tableId));
    }

    private RoomArea area(RestaurantTable table) {
        return areas.findById(table.getAreaId()).orElseThrow();
    }
}
