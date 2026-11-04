package com.xavier.backend.dining.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.dining.exception.DiningCodeTakenException;
import com.xavier.backend.dining.exception.DiningNotFoundException;
import com.xavier.backend.dining.exception.StillOccupiedException;
import com.xavier.backend.dining.model.RoomArea;
import com.xavier.backend.dining.model.dto.RoomAreaCreateRequest;
import com.xavier.backend.dining.model.dto.RoomAreaSummary;
import com.xavier.backend.dining.model.dto.RoomAreaUpdateRequest;
import com.xavier.backend.dining.repository.RoomAreaRepository;
import com.xavier.backend.dining.repository.TableOccupationRepository;
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
 * Room area configuration (spec §5 "Atendimento"). Every use case needs {@code DINING_MANAGE} in the area's
 * store; for an existing area that is the store it was loaded with, never one the client sends.
 */
@Service
public class RoomAreaService {

    static final String ENTITY = "RoomArea";

    private final RoomAreaRepository areas;
    private final TableOccupationRepository occupations;
    private final DiningRows rows;
    private final OrganizationApi organization;
    private final AccessPolicy accessPolicy;
    private final DiningChanges audit;
    private final Clock clock;

    RoomAreaService(RoomAreaRepository areas, TableOccupationRepository occupations, DiningRows rows,
                    OrganizationApi organization, AccessPolicy accessPolicy, DiningChanges audit, Clock clock) {
        this.areas = areas;
        this.occupations = occupations;
        this.rows = rows;
        this.organization = organization;
        this.accessPolicy = accessPolicy;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public RoomAreaSummary create(RoomAreaCreateRequest request) {
        accessPolicy.require(Permission.DINING_MANAGE, request.storeId());
        StoreChecks.requireExisting(organization, request.storeId());
        if (areas.existsByStoreIdAndCode(request.storeId(), request.code().strip())) {
            throw new DiningCodeTakenException(ENTITY, request.code());
        }
        RoomArea area = saveWithUniqueCode(
                RoomArea.create(request.storeId(), request.code(), request.name(), clock.instant()));
        record("ROOM_AREA_CREATED", area);
        return area.toSummary();
    }

    @Transactional
    public RoomAreaSummary update(UUID id, RoomAreaUpdateRequest request) {
        accessPolicy.require(Permission.DINING_MANAGE,
                areas.findStoreIdById(id).orElseThrow(() -> new DiningNotFoundException(ENTITY, id)));
        RoomArea area = rows.lockArea(id);
        if (!request.active() && occupations.existsOpenInArea(id)) {
            throw new StillOccupiedException(ENTITY, id);
        }
        OptimisticLocking.requireVersion(RoomArea.class, id, area.getVersion(), request.version());
        if (areas.existsByStoreIdAndCodeAndIdNot(area.getStoreId(), request.code().strip(), id)) {
            throw new DiningCodeTakenException(ENTITY, request.code());
        }
        area.update(request.code(), request.name(), request.active(), clock.instant());
        saveWithUniqueCode(area); // flush now: a concurrent writer fails here (409), and the response has the new version
        record("ROOM_AREA_UPDATED", area);
        return area.toSummary();
    }

    @Transactional(readOnly = true)
    public RoomAreaSummary get(UUID id) {
        RoomArea area = area(id);
        accessPolicy.require(Permission.DINING_MANAGE, area.getStoreId());
        return area.toSummary();
    }

    @Transactional(readOnly = true)
    public PageResponse<RoomAreaSummary> list(UUID storeId, Pageable pageable) {
        accessPolicy.require(Permission.DINING_MANAGE, storeId);
        return PageResponse.from(areas.findByStoreId(storeId, pageable).map(RoomArea::toSummary));
    }

    private RoomArea saveWithUniqueCode(RoomArea area) {
        return UniqueConstraints.saveAndFlush(areas, area,
                () -> new DiningCodeTakenException(ENTITY, area.getCode()), "room_area_store_code_uk");
    }

    private RoomArea area(UUID id) {
        return areas.findById(id).orElseThrow(() -> new DiningNotFoundException(ENTITY, id));
    }

    private void record(String action, RoomArea area) {
        audit.record(action, ENTITY, area.getId(), area.getStoreId(),
                Map.of("code", area.getCode(), "active", area.isActive()));
    }
}
