package com.xavier.backend.inventory.recipe.model;

/** The life of a recipe version or a consumption policy version (D115, D116): DRAFT → ACTIVE → SUPERSEDED. */
public enum VersionStatus {
    DRAFT,
    ACTIVE,
    SUPERSEDED
}
