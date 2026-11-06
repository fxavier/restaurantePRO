package com.xavier.backend.catalog.repository;

import java.util.UUID;

/** Only the store of a store-or-company-wide entry ({@code null} = every store): read before authorizing (D42). */
public interface StoreScope {

    UUID getStoreId();
}
