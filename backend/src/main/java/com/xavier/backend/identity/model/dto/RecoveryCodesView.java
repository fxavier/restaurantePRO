package com.xavier.backend.identity.model.dto;

import java.util.List;

/** A new set of recovery codes, replacing all earlier ones; shown once, never stored in clear (D139). */
public record RecoveryCodesView(List<String> recoveryCodes) {

    public RecoveryCodesView {
        recoveryCodes = List.copyOf(recoveryCodes);
    }

    @Override
    public String toString() {
        return "RecoveryCodesView[" + recoveryCodes.size() + " codes]";
    }
}
