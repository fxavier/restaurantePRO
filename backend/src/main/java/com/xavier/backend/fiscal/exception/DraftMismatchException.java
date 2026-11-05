package com.xavier.backend.fiscal.exception;

import com.xavier.backend.shared.exception.ConflictException;
import java.util.Map;
import java.util.UUID;

/**
 * A source drafted again with a command its existing DRAFT cannot take (409 {@code DRAFT_MISMATCH}): other lines or
 * totals, or undoing the on-account mark or its customer. The caller re-reads the document and its own state.
 */
public class DraftMismatchException extends ConflictException {

    public static final String CODE = "DRAFT_MISMATCH";

    private DraftMismatchException(UUID documentId, String message) {
        super(CODE, message, Map.of("documentId", documentId));
    }

    public static DraftMismatchException lines(UUID documentId) {
        return new DraftMismatchException(documentId, "The draft document " + documentId
                + " has other lines or totals than this command.");
    }

    public static DraftMismatchException onAccount(UUID documentId) {
        return new DraftMismatchException(documentId, "The draft document " + documentId
                + " is on account: it stays on account for the same customer.");
    }
}
