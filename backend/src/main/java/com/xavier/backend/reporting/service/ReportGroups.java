package com.xavier.backend.reporting.service;

/** Key expressions shared by the report groupings. */
final class ReportGroups {

    /** No group: every row of the projection falls under the {@code null} key. */
    static final String NONE = "NULL::text";

    private ReportGroups() {
    }
}
