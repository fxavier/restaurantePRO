package com.xavier.archfixture.tenant.catalog.service;

import java.util.UUID;
import org.springframework.cache.annotation.Cacheable;

/** Fixture: a Spring cache keyed without the tenant. */
public class CachedCatalog {

    @Cacheable("items")
    public String item(UUID id) {
        return id.toString();
    }
}
