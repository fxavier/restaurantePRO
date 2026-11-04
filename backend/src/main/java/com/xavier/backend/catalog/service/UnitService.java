package com.xavier.backend.catalog.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.catalog.exception.CatalogCodeTakenException;
import com.xavier.backend.catalog.exception.CatalogEntryNotFoundException;
import com.xavier.backend.catalog.model.Unit;
import com.xavier.backend.catalog.model.dto.UnitCreateRequest;
import com.xavier.backend.catalog.model.dto.UnitSummary;
import com.xavier.backend.catalog.model.dto.UnitUpdateRequest;
import com.xavier.backend.catalog.repository.UnitRepository;
import com.xavier.backend.shared.persistence.OptimisticLocking;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import com.xavier.backend.shared.web.PageResponse;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Sale units are company-wide: installation-wide {@code CATALOG_MANAGE}. */
@Service
public class UnitService {

    private final UnitRepository units;
    private final AccessPolicy accessPolicy;
    private final CatalogAudit audit;
    private final Clock clock;

    UnitService(UnitRepository units, AccessPolicy accessPolicy, CatalogAudit audit, Clock clock) {
        this.units = units;
        this.accessPolicy = accessPolicy;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public UnitSummary create(UnitCreateRequest request) {
        accessPolicy.require(Permission.CATALOG_MANAGE);
        Unit unit = save(Unit.create(request.code(), request.name(), clock.instant()));
        audit("UNIT_CREATED", unit);
        return unit.toSummary();
    }

    @Transactional
    public UnitSummary update(UUID id, UnitUpdateRequest request) {
        accessPolicy.require(Permission.CATALOG_MANAGE);
        Unit unit = unit(id);
        OptimisticLocking.requireVersion(Unit.class, id, unit.getVersion(), request.version());
        unit.update(request.code(), request.name(), clock.instant());
        save(unit);
        audit("UNIT_UPDATED", unit);
        return unit.toSummary();
    }

    @Transactional(readOnly = true)
    public UnitSummary get(UUID id) {
        accessPolicy.require(Permission.CATALOG_MANAGE);
        return unit(id).toSummary();
    }

    @Transactional(readOnly = true)
    public PageResponse<UnitSummary> list(Pageable pageable) {
        accessPolicy.require(Permission.CATALOG_MANAGE);
        return PageResponse.from(units.findAll(pageable).map(Unit::toSummary));
    }

    private Unit save(Unit unit) {
        return UniqueConstraints.saveAndFlush(units, unit,
                () -> new CatalogCodeTakenException("Unit code '" + unit.getCode() + "'"), "unit_code_uk");
    }

    private Unit unit(UUID id) {
        return units.findById(id).orElseThrow(() -> new CatalogEntryNotFoundException("Unit", id));
    }

    private void audit(String action, Unit unit) {
        audit.record(action, "Unit", unit.getId(), null, Map.of("code", unit.getCode()));
    }
}
