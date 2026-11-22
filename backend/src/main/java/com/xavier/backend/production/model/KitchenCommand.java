package com.xavier.backend.production.model;

import java.time.Instant;
import java.util.UUID;

/** What a kitchen display command does to a round (ADR 0044), and the audit action it records. */
public enum KitchenCommand {
    START("PRODUCTION_TICKET_STARTED"),
    READY("PRODUCTION_TICKET_READY"),
    BUMP("PRODUCTION_TICKET_BUMPED"),
    RECALL("PRODUCTION_TICKET_RECALLED");

    private final String auditAction;

    KitchenCommand(String auditAction) {
        this.auditAction = auditAction;
    }

    public String auditAction() {
        return auditAction;
    }

    /** Applies this command to {@code progress} as {@code employee} at {@code now}. */
    public void applyTo(KitchenProgress progress, UUID employee, Instant now) {
        switch (this) {
            case START -> progress.start(employee, now);
            case READY -> progress.ready(employee, now);
            case BUMP -> progress.bump(employee, now);
            case RECALL -> progress.recall(employee, now);
        }
    }
}
