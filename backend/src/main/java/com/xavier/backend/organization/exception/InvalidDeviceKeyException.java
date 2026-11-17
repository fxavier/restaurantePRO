package com.xavier.backend.organization.exception;

import com.xavier.backend.shared.exception.BusinessRuleException;

/** The device's public key is not a public EC P-256 JWK (D182); answered 400 by the enrolment route. */
public class InvalidDeviceKeyException extends BusinessRuleException {

    public static final String CODE = "DEVICE_KEY_INVALID";

    public InvalidDeviceKeyException() {
        super(CODE, "The device key must be a public EC P-256 JWK.");
    }
}
