package com.xavier.backend.audit.service;

import com.xavier.backend.audit.exception.AuditQueryException;
import com.xavier.backend.audit.model.dto.AuditEventView;
import com.xavier.backend.audit.model.dto.AuditFilter;
import com.xavier.backend.audit.repository.AuditEventRepository;
import java.time.Duration;
import java.util.stream.Stream;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** A validated filter and the stores it may read: the one place that spells out the repository's filter list. */
record AuditCriteria(AuditFilter filter, StoreVisibility visibility) {

    static final long MAX_RANGE_DAYS = 92;

    /** @throws AuditQueryException {@code RANGE_TOO_LARGE} when {@code to - from} is over 92 days */
    AuditCriteria {
        if (Duration.between(filter.from(), filter.to()).compareTo(Duration.ofDays(MAX_RANGE_DAYS)) > 0) {
            throw AuditQueryException.rangeTooLarge(MAX_RANGE_DAYS);
        }
    }

    Page<AuditEventView> page(AuditEventRepository events, Pageable pageable) {
        return events.findMatching(filter.from(), filter.to(), visibility.allStores(), visibility.stores(),
                filter.actorId(), filter.actorKind(), filter.action(), filter.entityType(), filter.entityId(),
                pageable);
    }

    long count(AuditEventRepository events) {
        return events.countMatching(filter.from(), filter.to(), visibility.allStores(), visibility.stores(),
                filter.actorId(), filter.actorKind(), filter.action(), filter.entityType(), filter.entityId());
    }

    Stream<AuditEventView> stream(AuditEventRepository events) {
        return events.streamMatching(filter.from(), filter.to(), visibility.allStores(), visibility.stores(),
                filter.actorId(), filter.actorKind(), filter.action(), filter.entityType(), filter.entityId());
    }
}
