package com.xavier.backend.access;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The store scope ("âmbito de loja", D164) a database connection is checked out with: the stores whose rows the
 * row-level security policies let it see and write. Written into {@code app.all_stores}, {@code app.store_ids} and
 * {@code app.scope_origin} at every checkout (D165). Coarse on purpose (D162): the stores where the actor holds
 * <em>any</em> permission; {@link AccessPolicy} stays the authority for decisions.
 *
 * @param allStores every store (an installation-wide account, a system scope of reach ALL)
 * @param stores    the stores of the scope when not {@code allStores}; {@code null} only for {@link #UNSET}
 * @param origin    who the scope is for, quoted in errors and logs only (never read by a policy)
 */
public record StoreScope(boolean allStores, Set<UUID> stores, String origin) {

    /** No scope: a protected table raises {@code RS001} (fail closed, P63). */
    public static final StoreScope UNSET = new StoreScope(false, null, "none");

    private static final String PERMISSION_PREFIX = "PERM_";

    public StoreScope {
        Objects.requireNonNull(origin, "origin");
        stores = stores == null ? null : Set.copyOf(stores);
    }

    /** D164 mapping of an authenticated actor; pure. */
    public static StoreScope of(Actor actor) {
        return switch (actor.kind()) {
            case ACCOUNT -> ofAccount(actor);
            case EMPLOYEE -> ofPosSession(actor);
        };
    }

    /** Every store (reach ALL). */
    public static StoreScope all(String origin) {
        return new StoreScope(true, Set.of(), origin);
    }

    /** An explicit empty scope: sees no store rows, without an error (reach NONE). */
    public static StoreScope none(String origin) {
        return new StoreScope(false, Set.of(), origin);
    }

    /** One store: a listener of an event of that store (D166). */
    public static StoreScope ofStore(UUID storeId, String origin) {
        return new StoreScope(false, Set.of(Objects.requireNonNull(storeId, "storeId")), origin);
    }

    /** {@code false} only for {@link #UNSET}. */
    public boolean isSet() {
        return stores != null;
    }

    /** The {@code app.all_stores} value: {@code on}, {@code off}, or {@code ''} when unset. */
    public String allStoresSetting() {
        if (!isSet()) {
            return "";
        }
        return allStores ? "on" : "off";
    }

    /** The {@code app.store_ids} value: a canonical {@code uuid[]} literal (sorted, lower case), or {@code ''}. */
    public String storeIdsLiteral() {
        if (!isSet()) {
            return "";
        }
        return stores.stream().map(id -> id.toString().toLowerCase())
                .collect(Collectors.toCollection(TreeSet::new)).stream()
                .collect(Collectors.joining(",", "{", "}"));
    }

    /** The same stores, under another origin (an executor task keeps its submitter's stores). */
    public StoreScope withOrigin(String newOrigin) {
        return new StoreScope(allStores, stores, newOrigin);
    }

    private static StoreScope ofAccount(Actor actor) {
        String origin = "account:" + actor.userId();
        Set<UUID> stores = new TreeSet<>();
        for (String authority : actor.authorities()) {
            if (!authority.startsWith(PERMISSION_PREFIX)) {
                continue;
            }
            int at = authority.indexOf('@');
            if (at < 0) {
                return all(origin);
            }
            parseStore(authority.substring(at + 1)).ifPresent(stores::add);
        }
        return new StoreScope(false, stores, origin);
    }

    private static StoreScope ofPosSession(Actor actor) {
        String origin = "employee:" + actor.employeeId() + "@" + actor.terminalId();
        UUID store = actor.storeId();
        if (store == null) {
            return none(origin);
        }
        boolean holdsAnyThere = actor.authorities().stream()
                .filter(authority -> authority.startsWith(PERMISSION_PREFIX))
                .anyMatch(authority -> authority.indexOf('@') < 0
                        || parseStore(authority.substring(authority.indexOf('@') + 1))
                        .filter(store::equals).isPresent());
        return holdsAnyThere ? ofStore(store, origin) : none(origin);
    }

    private static Optional<UUID> parseStore(String text) {
        try {
            return Optional.of(UUID.fromString(text));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
