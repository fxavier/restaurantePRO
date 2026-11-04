package com.xavier.backend.dining.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.dining.exception.AreaNotInStoreException;
import com.xavier.backend.dining.exception.DiningCodeTakenException;
import com.xavier.backend.dining.exception.DiningNotFoundException;
import com.xavier.backend.dining.model.RestaurantTable;
import com.xavier.backend.dining.model.RoomArea;
import com.xavier.backend.dining.model.dto.RestaurantTableCreateRequest;
import com.xavier.backend.dining.model.dto.RestaurantTableSummary;
import com.xavier.backend.dining.model.dto.RestaurantTableUpdateRequest;
import com.xavier.backend.dining.repository.RestaurantTableRepository;
import com.xavier.backend.dining.repository.RoomAreaRepository;
import com.xavier.backend.organization.OrganizationApi;
import com.xavier.backend.shared.persistence.OptimisticLocking;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import com.xavier.backend.shared.web.PageResponse;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Restaurant table configuration (spec §5 "Atendimento", §8.2). Every use case needs {@code DINING_MANAGE} in
 * the table's store; for an existing table that is the store it was loaded with, never one the client sends.
 */
@Service
public class RestaurantTableService {

    static final String ENTITY = "RestaurantTable";

    private final RestaurantTableRepository tables;
    private final RoomAreaRepository areas;
    private final OrganizationApi organization;
    private final AccessPolicy accessPolicy;
    private final DiningAudit audit;
    private final Clock clock;

    RestaurantTableService(RestaurantTableRepository tables, RoomAreaRepository areas, OrganizationApi organization,
                           AccessPolicy accessPolicy, DiningAudit audit, Clock clock) {
        this.tables = tables;
        this.areas = areas;
        this.organization = organization;
        this.accessPolicy = accessPolicy;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public RestaurantTableSummary create(RestaurantTableCreateRequest request) {
        accessPolicy.require(Permission.DINING_MANAGE, request.storeId());
        StoreChecks.requireExisting(organization, request.storeId());
        RoomArea area = areaOf(request.storeId(), request.areaId());
        if (tables.existsByStoreIdAndCode(request.storeId(), request.code().strip())) {
            throw new DiningCodeTakenException(ENTITY, request.code());
        }
        RestaurantTable table = saveWithUniqueCode(
                RestaurantTable.create(request.storeId(), area, request.code(), request.seats(), clock.instant()));
        record("RESTAURANT_TABLE_CREATED", table);
        return table.toSummary();
    }

    @Transactional
    public RestaurantTableSummary update(UUID id, RestaurantTableUpdateRequest request) {
        RestaurantTable table = table(id);
        accessPolicy.require(Permission.DINING_MANAGE, table.getStoreId());
        OptimisticLocking.requireVersion(RestaurantTable.class, id, table.getVersion(), request.version());
        RoomArea area = areaOf(table.getStoreId(), request.areaId());
        if (tables.existsByStoreIdAndCodeAndIdNot(table.getStoreId(), request.code().strip(), id)) {
            throw new DiningCodeTakenException(ENTITY, request.code());
        }
        table.update(area, request.code(), request.seats(), request.active(), clock.instant());
        saveWithUniqueCode(table); // flush now: a concurrent writer fails here (409), and the response has the new version
        record("RESTAURANT_TABLE_UPDATED", table);
        return table.toSummary();
    }

    @Transactional(readOnly = true)
    public RestaurantTableSummary get(UUID id) {
        RestaurantTable table = table(id);
        accessPolicy.require(Permission.DINING_MANAGE, table.getStoreId());
        return table.toSummary();
    }

    @Transactional(readOnly = true)
    public PageResponse<RestaurantTableSummary> list(UUID storeId, Pageable pageable) {
        accessPolicy.require(Permission.DINING_MANAGE, storeId);
        return PageResponse.from(tables.findByStoreId(storeId, pageable).map(RestaurantTable::toSummary));
    }

    /** An unknown area is answered like another store's area (422), so area ids of other stores stay hidden. */
    private RoomArea areaOf(UUID storeId, UUID areaId) {
        return areas.findById(areaId).orElseThrow(() -> new AreaNotInStoreException(areaId, storeId));
    }

    private RestaurantTable saveWithUniqueCode(RestaurantTable table) {
        return UniqueConstraints.saveAndFlush(tables, table,
                () -> new DiningCodeTakenException(ENTITY, table.getCode()), "restaurant_table_store_code_uk");
    }

    private RestaurantTable table(UUID id) {
        return tables.findById(id).orElseThrow(() -> new DiningNotFoundException(ENTITY, id));
    }

    private void record(String action, RestaurantTable table) {
        audit.record(action, ENTITY, table.getId(), table.getStoreId(), Map.of("code", table.getCode(),
                "areaId", table.getAreaId().toString(), "seats", table.getSeats(), "active", table.isActive()));
    }
}
