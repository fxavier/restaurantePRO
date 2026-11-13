package com.xavier.backend.promotions.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.promotions.PromotionsModuleTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * D102, §14 #7: a published promotion snapshot is a fact. The database holds it even for code that bypasses the
 * aggregate: never edited, never deleted; the release may only be retired.
 */
@PromotionsModuleTest
class PromotionSnapshotImmutableTest {

    static final String FROZEN = "{\"promotions\": []}";

    @Autowired
    JdbcClient jdbc;

    @Test
    void aPublishedSnapshotCannotBeEditedOrDeletedButTheReleaseCanBeRetired() {
        UUID release = UUID.randomUUID();
        insertPublished(UUID.randomUUID(), release, 1);

        assertThatThrownBy(() -> update(release, "snapshot = '{\"promotions\": [1]}'"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("immutable");
        assertThatThrownBy(() -> update(release, "version_no = 7"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("immutable");
        assertThatThrownBy(() -> update(release, "published_by_account_id = gen_random_uuid()"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM promotions.promotion_release WHERE id = :id")
                .param("id", release).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("immutable");

        update(release, "status = 'RETIRED', retired_at = now()");
        assertThatThrownBy(() -> update(release, "status = 'PUBLISHED', retired_at = NULL"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("immutable");
        assertThat(jdbc.sql("SELECT snapshot::text FROM promotions.promotion_release WHERE id = :id")
                .param("id", release).query(String.class).single()).isEqualTo(FROZEN);
    }

    @Test
    void aStoreCannotHaveTwoPublishedReleases() {
        UUID store = UUID.randomUUID();
        insertPublished(store, UUID.randomUUID(), 1);

        assertThatThrownBy(() -> insertPublished(store, UUID.randomUUID(), 2))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("promotion_release_one_published_per_store_ux");
    }

    @Test
    void aPublishedReleaseMustCarryItsSnapshot() {
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO promotions.promotion_release (id, store_id, status, created_by_account_id, created_at,
                                                                  version)
                        VALUES (:id, :store, 'PUBLISHED', :author, now(), 0)""")
                .param("id", UUID.randomUUID()).param("store", UUID.randomUUID()).param("author", UUID.randomUUID())
                .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("promotion_release_frozen_ck");
    }

    @Test
    void theWarningsOfAPublishedOrRetiredReleaseCannotBeRewritten() {
        UUID release = UUID.randomUUID();
        insertPublished(UUID.randomUUID(), release, 1);
        String change = "validation_warnings = '[{\"code\": \"X\"}]'";

        assertThatThrownBy(() -> update(release, change))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("immutable");
        update(release, "status = 'RETIRED', retired_at = now()");
        assertThatThrownBy(() -> update(release, change))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("immutable");
        assertThat(jdbc.sql("SELECT validation_warnings::text FROM promotions.promotion_release WHERE id = :id")
                .param("id", release).query(String.class).single()).isEqualTo("[]");
    }

    private void update(UUID release, String change) {
        jdbc.sql("UPDATE promotions.promotion_release SET " + change + " WHERE id = :id").param("id", release).update();
    }

    private void insertPublished(UUID store, UUID id, long version) {
        jdbc.sql("""
                        INSERT INTO promotions.promotion_release (id, store_id, status, version_no, snapshot, effective_at,
                            published_by_account_id, created_by_account_id, created_at, version, validation_warnings)
                        VALUES (:id, :store, 'PUBLISHED', :version, :snapshot::jsonb, now(), :author, :author, now(), 0,
                                '[]')""")
                .param("id", id).param("store", store).param("version", version).param("snapshot", FROZEN)
                .param("author", UUID.randomUUID()).update();
    }
}
