package com.xavier.backend.production.model;

/** How a FAILED or UNKNOWN job was dealt with: someone saw the paper, or a new job (a retry) replaced it. */
public enum PrintResolution {
    CONFIRMED_PRINTED,
    RETRIED
}
