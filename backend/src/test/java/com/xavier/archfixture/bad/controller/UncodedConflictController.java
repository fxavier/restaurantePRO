package com.xavier.archfixture.bad.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Violates CONFLICT_IS_ANSWERED_ONLY_BY_THE_SHARED_HANDLER: a 409 with no code, outside the shared handler. */
public class UncodedConflictController {

    public void refuse() {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "taken");
    }
}
