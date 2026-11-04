package com.xavier.backend.identity;

import org.springframework.security.access.AccessDeniedException;

/**
 * A supervisor approval was refused: unknown code, wrong PIN, locked or inactive approver, or no such permission
 * in that store. One answer for all (403, the operator's own session stays valid); the reason is logged at WARN.
 */
public class ApprovalDeniedException extends AccessDeniedException {

    public ApprovalDeniedException() {
        super("The approval was refused.");
    }
}
