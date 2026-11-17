package com.xavier.backend.access;

import java.util.Optional;
import java.util.UUID;

/**
 * SPI: the {@link Actor} of a resolved principal, built from the tenant's own identity rows (D177 step 7). {@code
 * identity} implements it; the request pipeline ({@code config.security}) calls it inside the resolved tenant's
 * binding, so row-level security applies to the lookup itself. Empty = the principal cannot act (inactive account,
 * ended grant, expired POS session): the caller refuses the request.
 */
public interface ActorDirectory {

    /** The BackOffice account of an {@code ACCOUNT} membership, with its permissions. */
    Optional<Actor> account(UUID accountId);

    /**
     * The support account of a {@code SUPPORT} membership's grant (D183), while the grant is usable; its first use
     * notifies the tenant's administrators.
     */
    Optional<Actor> support(UUID grantId);

    /**
     * The employee of an interim POS session token ({@code X-Pos-Session}, until S73), on its terminal and store; the
     * session's idle time restarts.
     */
    Optional<Actor> posSession(String token);
}
