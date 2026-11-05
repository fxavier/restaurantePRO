package com.xavier.backend.production.model;

/**
 * Spec §8.6 Normal/Espera/Ignorar/Redirecionar. HOLD keeps jobs QUEUED; IGNORE (needs {@code PRODUCTION_SUPPRESS})
 * creates tickets marked suppressed and no job, so a suppression is visible and is not a failure; REDIRECT sends new
 * jobs to another printer of the same store and moves the undelivered ones (D56).
 */
public enum PrinterMode {
    NORMAL,
    HOLD,
    IGNORE,
    REDIRECT
}
