package com.xavier.backend.production.exception;

import com.xavier.backend.shared.exception.ConflictException;
import java.util.Map;
import java.util.UUID;

/** A print job command that its state does not allow (409 with a stable {@code code}). */
public class PrintJobConflictException extends ConflictException {

    private PrintJobConflictException(String code, String message, UUID jobId) {
        super(code, message, jobId == null ? Map.of() : Map.of("printJobId", jobId));
    }

    public static PrintJobConflictException illegalState(UUID jobId, Object status, String command) {
        return new PrintJobConflictException("ILLEGAL_PRINT_JOB_STATE",
                "Print job " + jobId + " cannot " + command + " while " + status, jobId);
    }

    public static PrintJobConflictException alreadyResolved(UUID jobId) {
        return new PrintJobConflictException("PRINT_JOB_ALREADY_RESOLVED",
                "Print job " + jobId + " was already acknowledged or retried", jobId);
    }

    public static PrintJobConflictException concurrentCopy(UUID ticketId) {
        return new PrintJobConflictException("REPRINT_IN_PROGRESS",
                "Another reprint of ticket " + ticketId + " took this copy number; try again", null);
    }
}
