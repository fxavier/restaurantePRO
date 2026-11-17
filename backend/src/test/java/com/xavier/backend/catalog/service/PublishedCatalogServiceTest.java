package com.xavier.backend.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.access.Actor;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.TestAuthentications;
import com.xavier.backend.catalog.CatalogModuleTest;
import com.xavier.backend.catalog.Publications;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * S77 (F6, as decided): the read hands over the stored snapshot text, unparsed, so the transaction ends before the
 * catalog is parsed and written (the controller does that with no transaction; the wire bytes are pinned by
 * {@code PosCatalogHttpTest}).
 */
@CatalogModuleTest
class PublishedCatalogServiceTest {

    @Autowired
    PublishedCatalogService service;
    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;

    @Test
    void theCurrentCatalogIsTheStoredSnapshotTextOfTheSessionsStore() throws Exception {
        Publications publications = new Publications(mvc, jdbc);
        UUID store = publications.sellableStore().store();
        UUID release = publications.publishNew(store);
        Map<String, Object> stored = OwnerDatabase.shared().jdbc().sql("""
                        SELECT catalog_version, snapshot::text AS snapshot FROM catalog.configuration_release
                        WHERE id = ?""").param(release).query().singleRow();

        PublishedCatalogService.Published published = asPos(store, () -> service.current(null));

        assertThat(published.releaseId()).isEqualTo(release);
        assertThat(published.storeId()).isEqualTo(store);
        assertThat(published.version()).isEqualTo(((Number) stored.get("catalog_version")).longValue());
        assertThat(published.snapshot()).isEqualTo(stored.get("snapshot"));
    }

    private static <T> T asPos(UUID store, java.util.function.Supplier<T> action) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(TestAuthentications.of(Actor.employee(UUID.randomUUID(), UUID.randomUUID(), store,
                Set.of(Permission.CATALOG_VIEW.authority(store)))));
        SecurityContextHolder.setContext(context);
        try {
            return action.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }
}
