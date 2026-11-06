package com.xavier.backend.settlement.service;

/**
 * A bounded provider call ({@link BoundedProviderCalls}) got no answer in time or could not be queued. Nothing is
 * known about it; the caller turns it into its own retryable error.
 */
public class ProviderUnavailableException extends RuntimeException {

    public ProviderUnavailableException(String providerCode, String operation, String reason) {
        super("Payment provider " + providerCode + " " + operation + ": " + reason);
    }
}
