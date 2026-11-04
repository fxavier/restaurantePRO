package com.xavier.backend.dining.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.dining.model.RestaurantTable;
import com.xavier.backend.dining.model.RoomArea;
import com.xavier.backend.dining.model.dto.TableMap;
import com.xavier.backend.dining.model.dto.TableStatus;
import com.xavier.backend.dining.repository.RestaurantTableRepository;
import com.xavier.backend.dining.repository.RoomAreaRepository;
import com.xavier.backend.organization.OrganizationApi;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The read-only POS table map (spec §5 "Atendimento", §9 {@code GET /api/v1/pos/tables}). */
@Service
public class TableMapService {

    private final RoomAreaRepository areas;
    private final RestaurantTableRepository tables;
    private final OrganizationApi organization;
    private final AccessPolicy accessPolicy;

    TableMapService(RoomAreaRepository areas, RestaurantTableRepository tables, OrganizationApi organization,
                    AccessPolicy accessPolicy) {
        this.areas = areas;
        this.tables = tables;
        this.organization = organization;
        this.accessPolicy = accessPolicy;
    }

    @Transactional(readOnly = true)
    public TableMap tableMap(UUID storeId) {
        accessPolicy.require(Permission.DINING_VIEW, storeId);
        StoreChecks.requireExisting(organization, storeId);
        Map<UUID, List<TableMap.Table>> tablesByArea = tables.findByStoreIdAndActiveTrueOrderByCode(storeId).stream()
                .collect(Collectors.groupingBy(RestaurantTable::getAreaId,
                        Collectors.mapping(this::toMapTable, Collectors.toList())));
        List<TableMap.Area> mapAreas = areas.findByStoreIdAndActiveTrueOrderByCode(storeId).stream()
                .map(area -> toMapArea(area, tablesByArea.getOrDefault(area.getId(), List.of())))
                .toList();
        return new TableMap(storeId, mapAreas);
    }

    private TableMap.Table toMapTable(RestaurantTable table) {
        return new TableMap.Table(table.getId(), table.getCode(), table.getSeats(), statusOf(table));
    }

    private static TableMap.Area toMapArea(RoomArea area, List<TableMap.Table> tables) {
        return new TableMap.Area(area.getId(), area.getCode(), area.getName(), tables);
    }

    /** The one place a table's state is decided. R2 derives it from the open TableOccupation. */
    private TableStatus statusOf(RestaurantTable table) {
        return TableStatus.FREE;
    }
}
