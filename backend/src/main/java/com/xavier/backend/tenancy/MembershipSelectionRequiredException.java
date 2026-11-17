package com.xavier.backend.tenancy;

/**
 * Several memberships of the subject match and no tenant was selected (D177 step 5): the caller answers 400
 * {@code TENANT_SELECTION_REQUIRED}, listing nothing. Its own type, so no other failure of the resolution is ever
 * mistaken for it.
 */
public class MembershipSelectionRequiredException extends IllegalStateException {

    public MembershipSelectionRequiredException() {
        super("Several memberships match and no tenant was selected");
    }
}
