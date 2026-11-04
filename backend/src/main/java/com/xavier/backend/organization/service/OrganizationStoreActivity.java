package com.xavier.backend.organization.service;

import com.xavier.backend.access.StoreActivity;
import com.xavier.backend.organization.model.Store;
import com.xavier.backend.organization.repository.StoreRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Organization's answer to {@code AccessPolicy.requirePos}: an unknown store is not active (D45). */
@Service
class OrganizationStoreActivity implements StoreActivity {

    private final StoreRepository stores;

    OrganizationStoreActivity(StoreRepository stores) {
        this.stores = stores;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isActive(UUID storeId) {
        return stores.findById(storeId).map(Store::isActive).orElse(false);
    }
}
