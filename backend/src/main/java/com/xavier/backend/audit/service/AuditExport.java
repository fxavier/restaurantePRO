package com.xavier.backend.audit.service;

/** An authorized, counted and recorded export, ready for {@link AuditExportService#writeCsv}. */
public final class AuditExport {

    final AuditCriteria criteria;

    AuditExport(AuditCriteria criteria) {
        this.criteria = criteria;
    }
}
