package com.xavier.backend.organization.model;

import com.xavier.backend.shared.tenancy.MembershipKind;
import java.util.UUID;

/**
 * What a store device is (D182, D187). Both are confidential clients of the devices realm, enrolled the same way with
 * a one-time code; they differ in their client id's prefix (which the API's route families check, D176) and in the
 * membership the client gets.
 */
public enum DeviceKind {
    /** A POS terminal: {@code pos-<id>}, a {@code TERMINAL} membership, the {@code /pos/**} routes. */
    POS("pos-", MembershipKind.TERMINAL),
    /** A print bridge on the store LAN: {@code bridge-<id>}, a {@code BRIDGE} membership, the {@code /bridge/**} routes. */
    PRINT_BRIDGE("bridge-", MembershipKind.BRIDGE);

    private final String clientPrefix;
    private final MembershipKind membership;

    DeviceKind(String clientPrefix, MembershipKind membership) {
        this.clientPrefix = clientPrefix;
        this.membership = membership;
    }

    /** The device's Keycloak client id. */
    public String clientId(UUID deviceId) {
        return clientPrefix + deviceId;
    }

    public MembershipKind membership() {
        return membership;
    }
}
