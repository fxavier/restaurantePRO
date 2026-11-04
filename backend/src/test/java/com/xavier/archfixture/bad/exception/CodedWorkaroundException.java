package com.xavier.archfixture.bad.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

/** Violates the coded-error rule: a module-local ErrorResponseException instead of a shared CodedException. */
public class CodedWorkaroundException extends ErrorResponseException {

    public CodedWorkaroundException() {
        super(HttpStatus.UNPROCESSABLE_CONTENT);
    }
}
