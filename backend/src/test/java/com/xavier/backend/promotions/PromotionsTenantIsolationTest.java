package com.xavier.backend.promotions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.AppRoleSession;
import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.access.Actor;
import com.xavier.backend.access.Permission;
import com.xavier.backend.architecture.RowLevelSecurityCoverageTest;
import com.xavier.backend.shared.persistence.StoreScopeViolations;
import com.xavier.backend.shared.tenancy.MembershipKind;
import com.xavier.backend.shared.tenancy.StrictTenantScope;
import com.xavier.backend.shared.tenancy.TenantContext;
import com.xavier.backend.shared.tenancy.TenantTestSupport;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * A80, A82 and A84 (promotions): tenants A and B with the same promotion code, loyalty program version number, reward
 * code and customer. Through the API, B's promotion and promotion release addressed by id from A are absent (404) and
 * another store's release of A is forbidden (403, D42). With no {@code AccessPolicy} in front (the application-role
 * seam), B's rows are invisible to A, a row with B's {@code tenant_id} is refused ({@code 42501}), a row never moves
 * tenant ({@code RS002}), a child cannot reference B's parent (composite foreign key), and an unbound read raises
 * {@code RS003}. Strict: no bootstrap-tenant fallback.
 */
@PromotionsModuleTest
@StrictTenantScope
class PromotionsTenantIsolationTest {

    static final UUID A = TenantTestSupport.A;
    static final UUID B = TenantTestSupport.B;

    static final UUID A_STORE = UUID.fromString("0190b600-0000-7000-8000-0000000000a1");
    static final UUID A_OTHER_STORE = UUID.fromString("0190b600-0000-7000-8000-0000000000a2");
    static final UUID B_STORE = UUID.fromString("0190b600-0000-7000-8000-0000000000b1");

    static final UUID A_PROMOTION = UUID.fromString("0190b600-0000-7000-8000-0000000001a1");
    static final UUID B_PROMOTION = UUID.fromString("0190b600-0000-7000-8000-0000000001b1");
    static final UUID A_RELEASE = UUID.fromString("0190b600-0000-7000-8000-0000000002a1");
    static final UUID A_OTHER_RELEASE = UUID.fromString("0190b600-0000-7000-8000-0000000002a2");
    static final UUID B_RELEASE = UUID.fromString("0190b600-0000-7000-8000-0000000002b1");
    static final UUID A_VERSION = UUID.fromString("0190b600-0000-7000-8000-0000000003a1");
    static final UUID B_VERSION = UUID.fromString("0190b600-0000-7000-8000-0000000003b1");
    static final UUID A_CUSTOMER = UUID.fromString("0190b600-0000-7000-8000-0000000004a1");
    static final UUID B_CUSTOMER = UUID.fromString("0190b600-0000-7000-8000-0000000004b1");

    static final List<String> TABLES = List.of("promotion", "promotion_scope", "promotion_window", "promotion_store",
            "promotion_release", "discount_limits", "loyalty_program_version", "loyalty_program_scope",
            "loyalty_reward", "loyalty_account", "loyalty_point_entry", "loyalty_allocation");

    @Autowired
    MockMvc mvc;

    @BeforeAll
    static void twoTenantsWithIdenticalCodes() {
        TenantTestSupport.ensureTenants();
        seed(A, A_STORE, A_PROMOTION, A_RELEASE, A_VERSION, A_CUSTOMER);
        seed(B, B_STORE, B_PROMOTION, B_RELEASE, B_VERSION, B_CUSTOMER);
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            app.jdbc().sql("INSERT INTO promotions.promotion_release (id, store_id, status, created_by_account_id, "
                    + "created_at, version) VALUES (?, ?, 'DRAFT', ?, now(), 0)")
                    .param(A_OTHER_RELEASE).param(A_OTHER_STORE).param(UUID.randomUUID()).update();
        }
    }

    /** As the application role in {@code tenant}: no tenant_id is written, the column default takes the bound one. */
    private static void seed(UUID tenant, UUID store, UUID promotion, UUID release, UUID version, UUID customer) {
        try (AppRoleSession app = AppRoleSession.open().tenant(tenant).allStores()) {
            app.jdbc().sql("INSERT INTO promotions.promotion (id, code, name, kind, percent, priority, "
                    + "members_only, active, created_at, updated_at, version) VALUES (?, 'ISO-PROMO', 'Happy', "
                    + "'PERCENT_OFF', 10, 1, false, true, now(), now(), 0)").param(promotion).update();
            app.jdbc().sql("INSERT INTO promotions.promotion_store (promotion_id, store_id) VALUES (?, ?)")
                    .param(promotion).param(store).update();
            app.jdbc().sql("INSERT INTO promotions.promotion_window (promotion_id, position, days_of_week, "
                    + "local_start, local_end) VALUES (?, 0, ARRAY[1,2], '17:00', '19:00')").param(promotion).update();
            app.jdbc().sql("INSERT INTO promotions.promotion_release (id, store_id, status, created_by_account_id, "
                    + "created_at, version) VALUES (?, ?, 'DRAFT', ?, now(), 0)")
                    .param(release).param(store).param(UUID.randomUUID()).update();
            app.jdbc().sql("INSERT INTO promotions.discount_limits (store_id, free_limit_percent, max_percent, "
                    + "updated_at, version) VALUES (?, 10, 50, now(), 0)").param(store).update();
            app.jdbc().sql("INSERT INTO promotions.loyalty_program_version (id, version_no, status, spend_unit, "
                    + "currency, points_per_unit, created_at, version) VALUES (?, 1, 'DRAFT', 100, 'MZN', 1, now(), "
                    + "0)").param(version).update();
            app.jdbc().sql("INSERT INTO promotions.loyalty_reward (id, version_id, code, name, points_cost, effect) "
                    + "VALUES (?, ?, 'ISO-REWARD', 'Free drink', 100, 'FREE_UNIT')")
                    .param(UUID.randomUUID()).param(version).update();
            app.jdbc().sql("INSERT INTO promotions.loyalty_account (customer_id, status, enrolled_at, "
                    + "enrolled_by_actor_kind, version) VALUES (?, 'ACTIVE', now(), 'SYSTEM', 0)").param(customer)
                    .update();
            app.jdbc().sql("INSERT INTO promotions.loyalty_point_entry (id, customer_id, kind, points, source_type, "
                    + "source_id, reason, actor_kind, occurred_at) VALUES (?, ?, 'ADJUSTMENT', 5, 'ADJUSTMENT', ?, "
                    + "'welcome', 'SYSTEM', now())").param(UUID.randomUUID()).param(customer).param(UUID.randomUUID())
                    .update();
        }
    }

    @Test
    void bothTenantsUseTheSamePromotionRewardAndProgramVersionNumber() {
        assertThat(OwnerDatabase.shared().jdbc().sql("SELECT p.tenant_id FROM promotions.promotion p "
                        + "WHERE p.code = 'ISO-PROMO'").query(UUID.class).list()).containsExactlyInAnyOrder(A, B);
        assertThat(OwnerDatabase.shared().jdbc().sql("SELECT tenant_id FROM promotions.loyalty_program_version "
                        + "WHERE version_no = 1 AND id IN (?, ?)").param(A_VERSION).param(B_VERSION)
                .query(UUID.class).list()).containsExactlyInAnyOrder(A, B);
    }

    @Test
    void aTenantSeesOnlyItsOwnRows() {
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            assertThat(app.jdbc().sql("SELECT id FROM promotions.promotion WHERE code = 'ISO-PROMO'")
                    .query(UUID.class).list()).containsExactly(A_PROMOTION);
            assertThat(app.jdbc().sql("SELECT customer_id FROM promotions.loyalty_account WHERE customer_id IN (?, ?)")
                    .param(A_CUSTOMER).param(B_CUSTOMER).query(UUID.class).list()).containsExactly(A_CUSTOMER);
            assertThat(app.jdbc().sql("SELECT count(*) FROM promotions.loyalty_point_entry WHERE customer_id = ?")
                    .param(B_CUSTOMER).query(Long.class).single()).isZero();
            assertThat(app.jdbc().sql("SELECT count(*) FROM promotions.promotion_release WHERE id = ?")
                    .param(B_RELEASE).query(Long.class).single()).isZero();
        }
    }

    @Test
    void anotherTenantsPromotionById404AndOwnPromotion200() throws Exception {
        mvc.perform(get("/api/v1/backoffice/promotions/" + B_PROMOTION)
                .with(in(A, null, Permission.PROMOTION_VIEW))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/backoffice/promotions/" + A_PROMOTION)
                .with(in(A, null, Permission.PROMOTION_VIEW))).andExpect(status().isOk());
    }

    @Test
    void anotherTenantsReleaseById404AndAnotherStoreOfTheTenant403() throws Exception {
        RequestPostProcessor managerOfAStore = in(A, A_STORE, Permission.PROMOTION_MANAGE);

        mvc.perform(get("/api/v1/backoffice/promotions/releases/" + B_RELEASE).with(managerOfAStore))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/backoffice/promotions/releases/" + A_OTHER_RELEASE).with(managerOfAStore))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/backoffice/promotions/releases/" + A_RELEASE).with(managerOfAStore))
                .andExpect(status().isOk());
    }

    @Test
    void aRowWithAnotherTenantsIdIsRefused() {
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            AppRoleSession.assertRefused(() -> app.jdbc().sql("INSERT INTO promotions.promotion (id, tenant_id, "
                    + "code, name, kind, percent, priority, members_only, active, created_at, updated_at, version) "
                    + "VALUES (?, ?, 'ISO-X', 'X', 'PERCENT_OFF', 10, 1, false, true, now(), now(), 0)")
                    .param(UUID.randomUUID()).param(B).update(), StoreScopeViolations.Kind.TENANT_WRITE_REFUSED);
        }
    }

    @Test
    void aRowNeverMovesToAnotherTenant() {
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            AppRoleSession.assertRefused(() -> app.jdbc().sql("UPDATE promotions.promotion SET tenant_id = ? "
                    + "WHERE id = ?").param(B).param(A_PROMOTION).update(), StoreScopeViolations.Kind.STORE_CHANGE);
        }
    }

    @Test
    void aChildCannotReferenceAnotherTenantsParent() {
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            assertThatThrownBy(() -> app.jdbc().sql("INSERT INTO promotions.promotion_store (promotion_id, "
                    + "store_id) VALUES (?, ?)").param(B_PROMOTION).param(A_STORE).update())
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .satisfies(error -> assertThat(AppRoleSession.sqlState(error)).isEqualTo("23503"));
            assertThatThrownBy(() -> app.jdbc().sql("INSERT INTO promotions.loyalty_point_entry (id, customer_id, "
                    + "kind, points, source_type, source_id, reason, actor_kind, occurred_at) VALUES (?, ?, "
                    + "'ADJUSTMENT', 5, 'ADJUSTMENT', ?, 'forged', 'SYSTEM', now())")
                    .param(UUID.randomUUID()).param(B_CUSTOMER).param(UUID.randomUUID()).update())
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .satisfies(error -> assertThat(AppRoleSession.sqlState(error)).isEqualTo("23503"));
        }
    }

    @Test
    void anUnboundReadFailsClosed() {
        try (AppRoleSession app = AppRoleSession.open().noTenant().allStores()) {
            for (String table : TABLES) {
                AppRoleSession.assertRefused(() -> app.jdbc().sql("SELECT count(*) FROM promotions." + table)
                        .query(Long.class).single(), StoreScopeViolations.Kind.TENANT_MISSING);
            }
        }
    }

    @Test
    void theModuleIsTenantProtected() {
        RowLevelSecurityCoverageTest.assertModuleTenantProtected("promotions");
    }

    /** An account of {@code tenant} (its resolved membership as the principal's details), in a store or everywhere. */
    private static RequestPostProcessor in(UUID tenant, UUID store, Permission permission) {
        String authority = store == null ? permission.authority() : permission.authority(store);
        Actor actor = Actor.account(UUID.randomUUID(), Set.of(authority));
        UsernamePasswordAuthenticationToken token = UsernamePasswordAuthenticationToken.authenticated(actor, null,
                List.of(new SimpleGrantedAuthority(authority)));
        token.setDetails(new TenantContext(tenant, MembershipKind.ACCOUNT, actor.userId(), "test:isolation"));
        return authentication(token);
    }
}
