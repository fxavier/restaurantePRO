package com.xavier.backend.access;

import java.util.UUID;

/**
 * SPI: is a store active (D45)? {@code organization} owns stores and implements it; {@code access} cannot depend on
 * {@code organization} (that would be a cycle). {@link AccessPolicy#requirePos} refuses an inactive store, which
 * covers the window before identity's {@code StoreDeactivated} listener ends the store's POS sessions.
 */
public interface StoreActivity {

    /** @return true when the store exists and is active */
    boolean isActive(UUID storeId);
}
