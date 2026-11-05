package com.xavier.backend.shared.events;

/**
 * An incomplete event publication, as the BackOffice and the gauges see it (D88). Both are not completed; they differ
 * in whether the automatic resubmission still retries them.
 */
public enum EventPublicationState {
    /** Not completed, with fewer than {@code restaurantpro.events.max-attempts} attempts: the job retries it. */
    INCOMPLETE,
    /**
     * Not completed after {@code max-attempts} attempts (Modulith counts the first delivery): the job gives up and a
     * person resubmits it.
     */
    FAILED
}
