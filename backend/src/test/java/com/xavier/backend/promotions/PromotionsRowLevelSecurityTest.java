package com.xavier.backend.promotions;

import static com.xavier.backend.catalog.Actors.inStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.access.Actor;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.StrictStoreScope;
import com.xavier.backend.architecture.RowLevelSecurityCoverageTest;
import com.xavier.backend.shared.persistence.StoreProbe;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Row-level security on promotions (D167, D168, A71, A72): a store's discount limits and promotion releases are
 * STORE; the promotions themselves and the loyalty program are company-wide (UNSCOPED, ADR 0030). Strict: the test
 * thread has no fixture fallback. Releases are created through the API as their store's manager; limits by the owner.
 */
@PromotionsModuleTest
@StrictStoreScope
class PromotionsRowLevelSecurityTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PromotionsFixtures fixtures;

    @Autowired
    StoreProbe probe;

    UUID storeA;
    UUID storeB;
    UUID releaseB;

    @BeforeEach
    void twoStoresWithLimitsAndAReleaseOfB() throws Exception {
        storeA = fixtures.storeWithoutCatalog();
        storeB = fixtures.storeWithoutCatalog();
        for (UUID store : List.of(storeA, storeB)) {
            OwnerDatabase.shared().jdbc().sql("""
                    INSERT INTO promotions.discount_limits (store_id, free_limit_percent, max_percent, updated_at,
                        version) VALUES (?, 10, 50, now(), 0)""").param(store).update();
        }
        releaseB = PromotionsFixtures.id(PromotionsFixtures.json(PromotionsFixtures.expect(
                fixtures.createRelease(storeB), 201)));
    }

    @AfterEach
    void noActor() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void thePromotionsSchemaIsClassified() {
        RowLevelSecurityCoverageTest.assertModuleClassified("promotions");

        assertThat(OwnerDatabase.shared().jdbc().sql("""
                        SELECT table_name::text FROM shared.rls_protected_table
                        WHERE table_name::text LIKE 'promotions.%'""").query(String.class).list())
                .containsExactlyInAnyOrder("promotions.discount_limits", "promotions.promotion_release");
        assertThat(OwnerDatabase.shared().jdbc().sql("""
                        SELECT count(*) FROM shared.rls_unscoped_table WHERE table_name::text LIKE 'promotions.%'""")
                .query(Long.class).single()).as("the company-wide promotions and loyalty program").isEqualTo(10);
    }

    @Test
    void aStoreAManagerNeitherReadsNorWritesStoreBsLimitsOrReleases() {
        actor(Permission.PROMOTION_MANAGE.authority(storeA));

        assertThat(limitsOf(storeA, storeB)).containsExactly(storeA);
        assertThat(jdbc.sql("UPDATE promotions.discount_limits SET max_percent = 100 WHERE store_id = ?")
                .param(storeB).update()).as("B's limits are not updatable").isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM promotions.promotion_release WHERE id = ?").param(releaseB)
                .query(Long.class).single()).isZero();
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO promotions.discount_limits (store_id, free_limit_percent, max_percent, updated_at,
                    version) VALUES (?, 10, 50, now(), 0)""").param(UUID.randomUUID()).update())
                .rootCause().hasMessageContaining("row-level security");

        assertThat(probe.storeOf("promotions.promotion_release", releaseB)).as("the D42 probe sees it")
                .contains(storeB);
    }

    @Test
    void aReleaseNeverMovesStoreEvenForAnAllStoresScope() {
        actor(Permission.PROMOTION_MANAGE.authority());

        assertThat(limitsOf(storeA, storeB)).containsExactlyInAnyOrder(storeA, storeB);
        assertThatThrownBy(() -> jdbc.sql("UPDATE promotions.promotion_release SET store_id = ? WHERE id = ?")
                .param(storeA).param(releaseB).update()).rootCause().hasMessageContaining("store change refused");
    }

    @Test
    void withNoActorAndNoScopeAReadFailsClosed() {
        assertThatThrownBy(() -> limitsOf(storeA, storeB)).rootCause().hasMessageContaining("store scope not set");
    }

    /** Through the API, another store's release by id is still 403 (D42 via shared.store_of), not 404. */
    @Test
    void anotherStoresReleaseByIdIsForbiddenNotMissing() throws Exception {
        mvc.perform(get(PromotionsFixtures.RELEASES + "/" + releaseB).with(inStore(storeA, Permission.PROMOTION_MANAGE)))
                .andExpect(status().isForbidden());
        mvc.perform(get(PromotionsFixtures.RELEASES + "/" + UUID.randomUUID())
                        .with(inStore(storeA, Permission.PROMOTION_MANAGE)))
                .andExpect(status().isNotFound());
        mvc.perform(get(PromotionsFixtures.RELEASES + "/" + releaseB).with(inStore(storeB, Permission.PROMOTION_MANAGE)))
                .andExpect(status().isOk());
    }

    private List<UUID> limitsOf(UUID... stores) {
        return jdbc.sql("SELECT store_id FROM promotions.discount_limits WHERE store_id IN (?, ?)")
                .param(stores[0]).param(stores[1]).query(UUID.class).list();
    }

    private static void actor(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                Actor.account(UUID.randomUUID(), Set.of(authorities)), null, List.of()));
    }
}
