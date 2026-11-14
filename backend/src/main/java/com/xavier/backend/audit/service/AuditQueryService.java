package com.xavier.backend.audit.service;

import com.xavier.backend.access.Permission;
import com.xavier.backend.audit.exception.AuditEventNotFoundException;
import com.xavier.backend.audit.model.dto.AuditEventView;
import com.xavier.backend.audit.model.dto.AuditFilter;
import com.xavier.backend.audit.repository.AuditEventRepository;
import com.xavier.backend.shared.persistence.StoreProbe;
import com.xavier.backend.shared.web.PageResponse;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading the audit trail (D82, spec §10), newest first ({@code occurredAt DESC, id DESC}; a client sort is ignored).
 * Plain reads are not audited.
 */
@Service
@Transactional(readOnly = true)
public class AuditQueryService {

    private final AuditEventRepository events;
    private final AuditAccess access;
    private final StoreProbe probe;

    AuditQueryService(AuditEventRepository events, AuditAccess access, StoreProbe probe) {
        this.events = events;
        this.access = access;
        this.probe = probe;
    }

    public PageResponse<AuditEventView> list(AuditFilter filter, Pageable pageable) {
        StoreVisibility visibility = access.visibleStores(filter.storeId(), Permission.AUDIT_VIEW);
        return PageResponse.from(new AuditCriteria(filter, visibility).page(events, unsorted(pageable)));
    }

    /**
     * D42 (b): only the row's store is read, then authorized (→ 403), then the row (absent → 404). The store is probed
     * through {@code shared.store_of}, which sees every store, because row-level security hides another store's row. It
     * answers no store both for a store-less row and for no row, and a store-less row needs an installation-wide grant
     * either way: so only an installation-wide auditor learns that an id does not exist (404); anyone else gets 403.
     */
    public AuditEventView get(UUID id) {
        access.requireToSee(probe.storeOf("audit.audit_event", id).orElse(null));
        return events.findViewById(id).orElseThrow(() -> new AuditEventNotFoundException(id));
    }

    /** Every row of one entity that the caller may see; rows of other stores are left out, not refused. */
    public PageResponse<AuditEventView> history(String entityType, String entityId, Pageable pageable) {
        StoreVisibility visibility = access.visibleStores(null, Permission.AUDIT_VIEW);
        return PageResponse.from(events.findHistory(entityType, entityId, visibility.allStores(),
                visibility.stores(), unsorted(pageable)));
    }

    private static Pageable unsorted(Pageable pageable) {
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
    }
}
