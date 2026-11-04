package com.xavier.backend.catalog.model;

/** Life cycle of a {@link ConfigurationRelease} (spec §8.3): DRAFT → VALIDATED → PUBLISHED → RETIRED. */
public enum ReleaseStatus {
    DRAFT,
    VALIDATED,
    PUBLISHED,
    RETIRED
}
