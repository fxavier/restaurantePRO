package com.xavier.backend.access;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Where the current actor holds a permission ({@link AccessPolicy#scopeOf}): installation-wide ({@code global}), and
 * the stores of its store-scoped grants. {@code scopeOf} never returns an empty one (it refuses an actor that holds it
 * nowhere); {@link AccessPolicy#storesWithAnyGrant} may (an account with no grant at all). A query
 * filters its rows with {@link #includes}; a global holder also sees rows of no store (system, company).
 *
 * @param global {@code true} when the permission is granted installation-wide
 * @param stores the stores of the store-scoped grants (may be non-empty for a global holder too)
 */
public record AccessScope(boolean global, Set<UUID> stores) {

    public AccessScope {
        stores = Set.copyOf(Objects.requireNonNull(stores, "stores"));
    }

    /** Whether a row of {@code storeId} is visible: always for a global holder, else only in a granted store. */
    public boolean includes(UUID storeId) {
        return global || (storeId != null && stores.contains(storeId));
    }
}
