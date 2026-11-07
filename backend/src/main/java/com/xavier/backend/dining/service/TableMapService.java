package com.xavier.backend.dining.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.dining.model.RestaurantTable;
import com.xavier.backend.dining.model.RoomArea;
import com.xavier.backend.dining.model.TableOccupation;
import com.xavier.backend.dining.model.dto.OccupationView;
import com.xavier.backend.dining.model.dto.TableMap;
import com.xavier.backend.dining.model.dto.TableStatus;
import com.xavier.backend.dining.repository.RestaurantTableRepository;
import com.xavier.backend.dining.repository.RoomAreaRepository;
import com.xavier.backend.dining.repository.TableOccupationRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read-only POS table map (spec §5 "Atendimento", §9 {@code GET /api/v1/pos/tables}): the session's store
 * (D39), each table with its state from {@link #statusOf}.
 */
@Service
public class TableMapService {

    private final RoomAreaRepository areas;
    private final RestaurantTableRepository tables;
    private final TableOccupationRepository occupations;
    private final AccessPolicy accessPolicy;
    private final ReservationIndicator reservations;

    TableMapService(RoomAreaRepository areas, RestaurantTableRepository tables, TableOccupationRepository occupations,
                    AccessPolicy accessPolicy, ReservationIndicator reservations) {
        this.areas = areas;
        this.tables = tables;
        this.occupations = occupations;
        this.accessPolicy = accessPolicy;
        this.reservations = reservations;
    }

    /**
     * @param requestedStoreId optional; when given it must be the session's store (403 otherwise, D39)
     */
    @Transactional(readOnly = true)
    public TableMap tableMap(UUID requestedStoreId) {
        UUID storeId = accessPolicy.requirePos(Permission.DINING_VIEW, requestedStoreId).storeId();
        Map<UUID, TableOccupation> openByTable = occupations.findByStoreIdAndClosedAtIsNull(storeId).stream()
                .collect(Collectors.toMap(TableOccupation::getTableId, Function.identity()));
        Map<UUID, Instant> nextReservations = reservations.nextByTable(storeId);
        Map<UUID, List<TableMap.Table>> tablesByArea = tables.findByStoreIdAndActiveTrueOrderByCode(storeId).stream()
                .collect(Collectors.groupingBy(RestaurantTable::getAreaId,
                        Collectors.mapping(table -> statusOf(table, openByTable.get(table.getId()),
                                        nextReservations.get(table.getId())),
                                Collectors.toList())));
        List<TableMap.Area> mapAreas = areas.findByStoreIdAndActiveTrueOrderByCode(storeId).stream()
                .map(area -> toMapArea(area, tablesByArea.getOrDefault(area.getId(), List.of())))
                .toList();
        return new TableMap(storeId, mapAreas);
    }

    private static TableMap.Area toMapArea(RoomArea area, List<TableMap.Table> tables) {
        return new TableMap.Area(area.getId(), area.getCode(), area.getName(), tables);
    }

    /**
     * The one place a table's state is decided (r2-decisions §4 dining): OCCUPIED from its open occupation (or
     * {@code null}), plus the independent locked, subtotal-printed and reserved indicators (§8.4, D126).
     *
     * @param nextReservationAt the start of the table's indicated reservation ({@link ReservationIndicator}), or
     *                          {@code null}
     */
    static TableMap.Table statusOf(RestaurantTable table, TableOccupation open, Instant nextReservationAt) {
        boolean reserved = nextReservationAt != null;
        if (open == null) {
            return new TableMap.Table(table.getId(), table.getCode(), table.getSeats(), TableStatus.FREE,
                    table.isLocked(), false, null, null, null, reserved, nextReservationAt);
        }
        OccupationView occupation = open.toView(table.getCode());
        return new TableMap.Table(table.getId(), table.getCode(), table.getSeats(), TableStatus.OCCUPIED,
                table.isLocked(), occupation.subtotalPrintedAt() != null, occupation.openedAt(), occupation.guests(),
                occupation.openedByEmployeeId(), reserved, nextReservationAt);
    }
}
