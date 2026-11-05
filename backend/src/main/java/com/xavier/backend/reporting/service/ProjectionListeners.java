package com.xavier.backend.reporting.service;

import com.xavier.backend.fiscal.model.event.FiscalDocumentIssued;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

/**
 * Reporting's inputs (D84): each owner's thin event triggers a pull of the immutable fact through a read-only facade,
 * in its own transaction after the owner's commit. The publication registry keeps the event until the projection
 * commits, so a failure stays pending (counted in {@code pendingFacts}) and is retried; every projection is an insert
 * keyed by the source id that does nothing when the row exists, so a redelivery changes nothing.
 */
@Service
public class ProjectionListeners {

    private final Projections projections;

    ProjectionListeners(Projections projections) {
        this.projections = projections;
    }

    @ApplicationModuleListener
    public void on(FiscalDocumentIssued event) {
        projections.projectDocument(event.documentId());
    }
}
