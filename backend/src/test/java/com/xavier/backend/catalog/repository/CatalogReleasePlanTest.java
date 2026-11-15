package com.xavier.backend.catalog.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.access.StoreScopes;
import com.xavier.backend.catalog.CatalogModuleTest;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D171: the release a store sells from ({@code findByStoreIdAndStatus(store, PUBLISHED)}, read on every order opened
 * and every POS catalog load) stays an index lookup by store under row-level security, as the application role in the
 * store's scope: the policy filters on top, and {@code store_id = …} (uuid, leakproof, D169) stays in the index
 * condition. Deterministic: a fixed seed of 12 stores × 150 retired releases and one published each, then
 * {@code ANALYZE} (as the owner) and {@code EXPLAIN} in one transaction with {@code enable_seqscan = off}.
 */
@CatalogModuleTest
class CatalogReleasePlanTest {

    /** {@code ConfigurationReleaseRepository.findByStoreIdAndStatus} as Hibernate writes it. */
    static final String PUBLISHED_RELEASE = """
            SELECT id, snapshot, catalog_version FROM catalog.configuration_release
            WHERE store_id = :store AND status = :status""";

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    StoreScopes scopes;

    UUID store;

    @BeforeEach
    void twelveStoresWithAHistoryOfReleases() {
        List<UUID> stores = Stream.generate(UUID::randomUUID).limit(12).toList();
        store = stores.getFirst();
        for (UUID seeded : stores) {
            OwnerDatabase.shared().jdbc().sql("""
                    INSERT INTO catalog.configuration_release (id, store_id, status, catalog_version, snapshot,
                        effective_at, published_by, created_by, created_at, validated_at, retired_at, lock_version)
                    SELECT uuidv7(), :store, CASE WHEN i = 150 THEN 'PUBLISHED' ELSE 'RETIRED' END, i, '{}'::jsonb,
                           now(), gen_random_uuid(), gen_random_uuid(), now(), now(),
                           CASE WHEN i = 150 THEN NULL ELSE now() END, 0
                    FROM generate_series(1, 150) i""").param("store", seeded).update();
        }
    }

    @Test
    void theStoresPublishedReleaseIsAnIndexLookupByStore() {
        String plan = scopes.callForStore(store, "plan-test", () -> tx.execute(status -> {
            OwnerDatabase.shared().jdbc().sql("ANALYZE catalog.configuration_release").update();
            assertThat(jdbc.sql("SELECT current_user").query(String.class).single())
                    .isEqualTo(TestcontainersConfiguration.APP_ROLE);
            jdbc.sql("SET LOCAL enable_seqscan = off").update();
            return String.join("\n", jdbc.sql("EXPLAIN " + PUBLISHED_RELEASE).param("store", store)
                    .param("status", "PUBLISHED").query(String.class).list());
        }));

        assertThat(plan).containsPattern("(Index Scan using|Bitmap Index Scan on) "
                        + "configuration_release_(one_published_per_store_ux|store_version_uk)")
                .containsPattern("Index Cond: \\(store_id = '" + store + "'::uuid\\)");
    }
}
