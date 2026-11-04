package com.xavier.backend.dining.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.dining.exception.DiningNotFoundException;
import com.xavier.backend.dining.model.RestaurantTable;
import com.xavier.backend.dining.model.dto.TableMap;
import com.xavier.backend.dining.repository.RestaurantTableRepository;
import com.xavier.backend.dining.repository.TableOccupationRepository;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * POS table lock and unlock (spec §4, §8.4): {@code TABLE_LOCK} in the session's store (D39). Locking a locked
 * table, or unlocking an unlocked one, changes nothing and records nothing.
 */
@Service
public class TableLockService {

    static final String ENTITY = RestaurantTableService.ENTITY;

    private final RestaurantTableRepository tables;
    private final TableOccupationRepository occupations;
    private final AccessPolicy accessPolicy;
    private final DiningChanges changes;
    private final DiningRows rows;
    private final Clock clock;

    TableLockService(RestaurantTableRepository tables, TableOccupationRepository occupations,
                     AccessPolicy accessPolicy, DiningChanges changes, DiningRows rows, Clock clock) {
        this.tables = tables;
        this.occupations = occupations;
        this.accessPolicy = accessPolicy;
        this.changes = changes;
        this.rows = rows;
        this.clock = clock;
    }

    @Transactional
    public TableMap.Table lock(UUID tableId, String reason) {
        PosContext pos = accessPolicy.requirePos(Permission.TABLE_LOCK);
        RestaurantTable table = tableOfSessionStore(tableId, pos);
        if (table.lock(pos.employeeId(), reason, clock.instant())) {
            changed("TABLE_LOCKED", table, Map.of("reason", table.getLockReason()));
        }
        return viewOf(table);
    }

    @Transactional
    public TableMap.Table unlock(UUID tableId) {
        PosContext pos = accessPolicy.requirePos(Permission.TABLE_LOCK);
        RestaurantTable table = tableOfSessionStore(tableId, pos);
        if (table.unlock(clock.instant())) {
            changed("TABLE_UNLOCKED", table, Map.of());
        }
        return viewOf(table);
    }

    /**
     * D42, after the session check: only the table's store (404 if absent), refuse another store (403, logged), then
     * lock the row.
     */
    private RestaurantTable tableOfSessionStore(UUID tableId, PosContext pos) {
        UUID storeId = tables.findStoreIdById(tableId).orElseThrow(() -> new DiningNotFoundException(ENTITY, tableId));
        accessPolicy.requireSessionStore(pos, storeId, ENTITY, tableId);
        return rows.lockTable(tableId);
    }

    private void changed(String action, RestaurantTable table, Map<String, Object> details) {
        tables.saveAndFlush(table); // the response carries the committed state
        changes.record(action, ENTITY, table.getId(), table.getStoreId(), details);
    }

    private TableMap.Table viewOf(RestaurantTable table) {
        return TableMapService.statusOf(table, occupations.findByTableIdAndClosedAtIsNull(table.getId()).orElse(null));
    }
}
