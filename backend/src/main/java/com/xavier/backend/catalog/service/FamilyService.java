package com.xavier.backend.catalog.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.catalog.exception.CatalogCodeTakenException;
import com.xavier.backend.catalog.exception.CatalogEntryNotFoundException;
import com.xavier.backend.catalog.exception.InvalidCatalogEntryException;
import com.xavier.backend.catalog.model.Family;
import com.xavier.backend.catalog.model.dto.FamilyCreateRequest;
import com.xavier.backend.catalog.model.dto.FamilySummary;
import com.xavier.backend.catalog.model.dto.FamilyUpdateRequest;
import com.xavier.backend.catalog.repository.FamilyRepository;
import com.xavier.backend.shared.web.PageResponse;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Families and subfamilies are company-wide: installation-wide {@code CATALOG_MANAGE}. */
@Service
public class FamilyService {

    private final FamilyRepository families;
    private final AccessPolicy accessPolicy;
    private final CatalogAudit audit;
    private final Clock clock;

    FamilyService(FamilyRepository families, AccessPolicy accessPolicy, CatalogAudit audit, Clock clock) {
        this.families = families;
        this.accessPolicy = accessPolicy;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public FamilySummary create(FamilyCreateRequest request) {
        accessPolicy.require(Permission.CATALOG_MANAGE);
        requireTopLevelParent(request.parentId(), null);
        Family family = save(Family.create(request.code(), request.name(), request.parentId(), clock.instant()));
        audit("FAMILY_CREATED", family);
        return family.toSummary();
    }

    @Transactional
    public FamilySummary update(UUID id, FamilyUpdateRequest request) {
        accessPolicy.require(Permission.CATALOG_MANAGE);
        Family family = family(id);
        OptimisticLocking.requireVersion(Family.class, id, family.getVersion(), request.version());
        requireTopLevelParent(request.parentId(), id);
        family.update(request.code(), request.name(), request.parentId(), clock.instant());
        save(family);
        audit("FAMILY_UPDATED", family);
        return family.toSummary();
    }

    @Transactional(readOnly = true)
    public FamilySummary get(UUID id) {
        accessPolicy.require(Permission.CATALOG_MANAGE);
        return family(id).toSummary();
    }

    @Transactional(readOnly = true)
    public PageResponse<FamilySummary> list(Pageable pageable) {
        accessPolicy.require(Permission.CATALOG_MANAGE);
        return PageResponse.from(families.findAll(pageable).map(Family::toSummary));
    }

    /** Subfamilies are one level deep: the parent is a top-level family, and a parent cannot become a child. */
    private void requireTopLevelParent(UUID parentId, UUID familyId) {
        if (parentId == null) {
            return;
        }
        if (parentId.equals(familyId)) {
            throw new InvalidCatalogEntryException("A family cannot be its own parent");
        }
        Family parent = families.findById(parentId)
                .orElseThrow(() -> new InvalidCatalogEntryException("Parent family " + parentId + " does not exist"));
        if (parent.isSubfamily()) {
            throw new InvalidCatalogEntryException("Parent " + parent.getCode() + " is a subfamily; only one level");
        }
        if (familyId != null && families.existsByParentId(familyId)) {
            throw new InvalidCatalogEntryException("A family with subfamilies cannot become a subfamily");
        }
    }

    private Family save(Family family) {
        return UniqueConstraint.saveAndFlush(families, family,
                () -> new CatalogCodeTakenException("Family code '" + family.getCode() + "'"), "family_code_uk");
    }

    private Family family(UUID id) {
        return families.findById(id).orElseThrow(() -> new CatalogEntryNotFoundException("Family", id));
    }

    private void audit(String action, Family family) {
        audit.record(action, "Family", family.getId(), null, Map.of("code", family.getCode()));
    }
}
