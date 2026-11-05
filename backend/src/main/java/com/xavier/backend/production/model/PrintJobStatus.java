package com.xavier.backend.production.model;

/**
 * One physical delivery attempt's state (r2-decisions §4). SENT means the TCP write and close succeeded, not that the
 * paper came out (§8.3). UNKNOWN is never resent on its own: it is acknowledged or retried as a new job.
 */
public enum PrintJobStatus {
    QUEUED,
    PROCESSING,
    SENT,
    RETRY_WAIT,
    FAILED,
    UNKNOWN
}
