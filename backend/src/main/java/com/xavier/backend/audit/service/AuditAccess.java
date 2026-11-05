package com.xavier.backend.audit.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.AccessScope;
import com.xavier.backend.access.Permission;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** The D82 scope rules on top of {@link AccessPolicy}, shared by the audit reads and the export. */
@Component
class AuditAccess {

    private static final Logger log = LoggerFactory.getLogger(AuditAccess.class);

    private final AccessPolicy accessPolicy;

    AuditAccess(AccessPolicy accessPolicy) {
        this.accessPolicy = accessPolicy;
    }

    /**
     * The rows the caller may read with every one of {@code permissions}. A named store must be granted (D42 (a), 403
     * before any read); otherwise the stores where all of them are held, installation-wide meaning every store.
     *
     * @throws AccessDeniedException (403) when the permissions are held nowhere, or in no common store
     */
    StoreVisibility visibleStores(UUID requestedStore, Permission... permissions) {
        if (requestedStore != null) {
            for (Permission permission : permissions) {
                accessPolicy.require(permission, requestedStore);
            }
            return StoreVisibility.only(Set.of(requestedStore));
        }
        Set<UUID> stores = null;
        for (Permission permission : permissions) {
            AccessScope scope = accessPolicy.scopeOf(permission);
            if (!scope.global()) {
                if (stores == null) {
                    stores = new HashSet<>(scope.stores());
                } else {
                    stores.retainAll(scope.stores());
                }
            }
        }
        if (stores == null) {
            return StoreVisibility.EVERYWHERE;
        }
        if (stores.isEmpty()) {
            log.warn("Access denied: user={} holds {} in no common store", accessPolicy.current().userId(),
                    Set.of(permissions));
            throw new AccessDeniedException("The permissions " + Set.of(permissions) + " share no store");
        }
        return StoreVisibility.only(stores);
    }

    /** D42 (b): a row of a store needs AUDIT_VIEW there; a store-less row needs it installation-wide. */
    void requireToSee(UUID rowStore) {
        if (rowStore == null) {
            accessPolicy.require(Permission.AUDIT_VIEW);
        } else {
            accessPolicy.require(Permission.AUDIT_VIEW, rowStore);
        }
    }
}
