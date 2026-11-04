package com.xavier.backend.catalog.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.catalog.CatalogModuleTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The database holds the release invariants even for code that bypasses the aggregate. */
@CatalogModuleTest
class ConfigurationReleaseConstraintsTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    void aStoreCannotHaveTwoPublishedReleases() {
        UUID store = UUID.randomUUID();
        insertPublished(store, UUID.randomUUID(), 1);

        assertThatThrownBy(() -> insertPublished(store, UUID.randomUUID(), 2))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("configuration_release_one_published_per_store_ux");
    }

    @Test
    void aPublishedSnapshotCannotBeEditedOrDeletedButTheReleaseCanBeRetired() {
        UUID release = UUID.randomUUID();
        insertPublished(UUID.randomUUID(), release, 1);

        assertThatThrownBy(() -> jdbc.sql("UPDATE catalog.configuration_release SET snapshot = '{\"items\": [1]}'"
                + " WHERE id = :id").param("id", release).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.sql("UPDATE catalog.configuration_release SET catalog_version = 7 WHERE id = :id")
                .param("id", release).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM catalog.configuration_release WHERE id = :id")
                .param("id", release).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("immutable");

        jdbc.sql("UPDATE catalog.configuration_release SET status = 'RETIRED', retired_at = now() WHERE id = :id")
                .param("id", release).update();
        assertThatThrownBy(() -> jdbc.sql("UPDATE catalog.configuration_release SET status = 'PUBLISHED',"
                + " retired_at = NULL WHERE id = :id").param("id", release).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("immutable");
        assertThat(jdbc.sql("SELECT snapshot::text FROM catalog.configuration_release WHERE id = :id")
                .param("id", release).query(String.class).single()).isEqualTo("{\"items\": []}");
    }

    @Test
    void aPublishedReleaseMustCarryItsFrozenFacts() {
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO catalog.configuration_release (id, store_id, status, created_by, created_at, lock_version)
                        VALUES (:id, :store, 'PUBLISHED', :author, now(), 0)""")
                .param("id", UUID.randomUUID()).param("store", UUID.randomUUID()).param("author", UUID.randomUUID()).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("configuration_release_frozen_ck");
    }

    private void insertPublished(UUID store, UUID id, long version) {
        jdbc.sql("""
                        INSERT INTO catalog.configuration_release (id, store_id, status, catalog_version, snapshot,
                            effective_at, published_by, created_by, created_at, lock_version)
                        VALUES (:id, :store, 'PUBLISHED', :version, '{"items": []}', now(), :author, :author, now(), 0)""")
                .param("id", id).param("store", store).param("version", version).param("author", UUID.randomUUID())
                .update();
    }
}
