package com.xavier.backend.customers;

import static com.xavier.backend.customers.CustomersFixtures.BO;
import static com.xavier.backend.customers.CustomersFixtures.POS;
import static com.xavier.backend.customers.CustomersFixtures.id;
import static com.xavier.backend.customers.CustomersFixtures.idem;
import static com.xavier.backend.customers.CustomersFixtures.json;
import static com.xavier.backend.customers.CustomersFixtures.mzn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.AppRoleSession;
import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.access.Actor;
import com.xavier.backend.access.Permission;
import com.xavier.backend.architecture.RowLevelSecurityCoverageTest;
import com.xavier.backend.shared.exception.NotFoundException;
import com.xavier.backend.shared.persistence.StoreScopeViolations;
import com.xavier.backend.shared.tenancy.MembershipKind;
import com.xavier.backend.shared.tenancy.StrictTenantScope;
import com.xavier.backend.shared.tenancy.TenantContext;
import com.xavier.backend.shared.tenancy.TenantScopes;
import com.xavier.backend.shared.tenancy.TenantTestSupport;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A80, A82, A84 and A88 for customers (R6, S74c): tenants A and B each have a customer "Ana Costa" with the same tax
 * id and phone, and a credit account. These tables were company-wide (UNSCOPED); they are now TENANT class. Through
 * the API B's customer addressed by id from A is absent (404), the search and the debts report show only A's, and the
 * credit ledger refuses B's customer. With no {@code AccessPolicy} in front, B's rows are invisible to A, a row with
 * B's {@code tenant_id} is refused ({@code 42501}), a row never moves tenant ({@code RS002}), a child cannot
 * reference B's parent (composite foreign key), and an unbound read raises {@code RS003}. Strict: no bootstrap-tenant
 * fallback.
 */
@CustomersModuleTest
@StrictTenantScope
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CustomersTenantIsolationTest {

    static final UUID A = TenantTestSupport.A;
    static final UUID B = TenantTestSupport.B;
    static final UUID ACCOUNT = UUID.fromString("0190b400-0000-7000-8000-0000000000c5");
    static final UUID A_STORE = UUID.fromString("0190b400-0000-7000-8000-0000000074a1");
    static final UUID B_STORE = UUID.fromString("0190b400-0000-7000-8000-0000000074b1");
    static final LocalDate DAY = LocalDate.of(2026, 11, 5);
    static final String PHONE = "841234567";
    static final String TAX_ID = "400123456";

    @Autowired
    MockMvc mvc;
    @Autowired
    CustomersApi customers;
    @Autowired
    TenantScopes tenants;
    @Autowired
    TransactionTemplate tx;

    UUID a;
    UUID b;

    @BeforeAll
    void twoTenantsWithIdenticalCustomers() {
        TenantTestSupport.ensureTenants();
        a = seed(A, A_STORE, "300.00");
        b = seed(B, B_STORE, "900.00");
        sale(A, a, "40.00");
        sale(B, b, "70.00");
    }

    /** As the application role in {@code tenant}: no tenant_id is written, the column default takes the bound one. */
    private static UUID seed(UUID tenant, UUID store, String limit) {
        UUID customer = UUID.randomUUID();
        try (AppRoleSession app = AppRoleSession.open().tenant(tenant).allStores()) {
            app.jdbc().sql("INSERT INTO organization.company_profile (id, name, tax_id, country, currency, version, "
                    + "created_at, updated_at) VALUES (?, 'Company', 'TAX-ISO', 'MZ', 'MZN', 0, now(), now()) "
                    + "ON CONFLICT (tenant_id) DO NOTHING").param(UUID.randomUUID()).update();
            app.jdbc().sql("INSERT INTO organization.store (id, code, name, timezone, active, version, created_at, "
                    + "updated_at) VALUES (?, 'CUS-1', 'Baixa', 'Africa/Maputo', true, 0, now(), now()) "
                    + "ON CONFLICT (tenant_id, code) DO NOTHING").param(store).update();
            app.jdbc().sql("INSERT INTO customers.customer (id, name, tax_id, active, created_at, updated_at, version) "
                    + "VALUES (?, 'Ana Costa', ?, true, now(), now(), 0)").param(customer).param(TAX_ID).update();
            app.jdbc().sql("INSERT INTO customers.customer_phone (id, customer_id, digits, is_primary) "
                    + "VALUES (?, ?, ?, true)").param(UUID.randomUUID()).param(customer).param(PHONE).update();
            app.jdbc().sql("INSERT INTO customers.credit_account (customer_id, credit_limit, currency, credit_blocked, "
                    + "version, created_at) VALUES (?, ?::numeric, 'MZN', false, 0, now())").param(customer)
                    .param(limit).update();
        }
        return customer;
    }

    private void sale(UUID tenant, UUID customer, String amount) {
        UUID charge = UUID.randomUUID();
        inTenant(tenant, () -> customers.reserve(customer, mzn(amount), charge));
        inTenant(tenant, () -> customers.postDebit(charge, UUID.randomUUID(), tenant == A ? A_STORE : B_STORE, DAY,
                mzn(amount)));
    }

    private <T> T inTenant(UUID tenant, java.util.function.Supplier<T> work) {
        return TenantTestSupport.inTenant(tenants, tenant, () -> tx.execute(status -> work.get()));
    }

    @Test
    void bothTenantsHaveTheSameCustomerAndTheSamePhone() {
        assertThat(OwnerDatabase.shared().jdbc().sql("SELECT tenant_id FROM customers.customer "
                        + "WHERE name = 'Ana Costa' AND tax_id = ?").param(TAX_ID).query(UUID.class).list())
                .containsExactlyInAnyOrder(A, B);
        assertThat(OwnerDatabase.shared().jdbc().sql("SELECT tenant_id FROM customers.customer_phone "
                        + "WHERE digits = ?").param(PHONE).query(UUID.class).list())
                .containsExactlyInAnyOrder(A, B);
    }

    @Test
    void aTenantSearchesOnlyItsOwnCustomers() throws Exception {
        String body = mvc.perform(get(BO).param("q", "Ana Costa").with(in(A, null, Permission.CUSTOMER_VIEW)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        List<String> ids = json(body).valueStream().map(row -> row.get("id").asString()).toList();
        assertThat(ids).contains(a.toString()).doesNotContain(b.toString());
        String atPos = mvc.perform(get(POS).param("q", "Ana Costa")
                        .with(atPos(A, A_STORE, Permission.CUSTOMER_VIEW))).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
        assertThat(atPos).contains(a.toString()).doesNotContain(b.toString());
    }

    @Test
    void anotherTenantsCustomerById404AndOwnCustomer200() throws Exception {
        RequestPostProcessor viewer = in(A, null, Permission.CUSTOMER_VIEW);

        mvc.perform(get(BO + "/" + b).with(viewer)).andExpect(status().isNotFound());
        mvc.perform(get(BO + "/" + b + "/phones").with(viewer)).andExpect(status().isNotFound());
        mvc.perform(get(BO + "/" + b + "/statement").with(viewer)).andExpect(status().isNotFound());
        mvc.perform(get(BO + "/" + b + "/credit").with(viewer)).andExpect(status().isNotFound());
        mvc.perform(get(POS + "/" + b).with(atPos(A, A_STORE, Permission.CUSTOMER_VIEW)))
                .andExpect(status().isNotFound());
        mvc.perform(get(BO + "/" + a).with(viewer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Ana Costa"));
        mvc.perform(get(BO + "/" + a + "/credit").with(viewer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").exists());
    }

    @Test
    void theDebtsReportShowsOnlyTheTenantsDebtors() throws Exception {
        mvc.perform(get(BO + "/debts").with(in(A, null, Permission.CUSTOMER_VIEW))).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.customerId == '%s')]".formatted(a)).isNotEmpty())
                .andExpect(jsonPath("$.content[?(@.customerId == '%s')]".formatted(b)).isEmpty());
    }

    @Test
    void theCreditLedgerNeverTouchesAnotherTenantsCustomer() {
        assertThatThrownBy(() -> inTenant(A, () -> customers.reserve(b, mzn("1.00"), UUID.randomUUID())))
                .isInstanceOf(NotFoundException.class);
        assertThat(inTenant(A, () -> customers.debtOf(a))).isEqualTo(mzn("40.00"));
        assertThat(inTenant(B, () -> customers.debtOf(b))).isEqualTo(mzn("70.00"));
        assertThat(inTenant(A, () -> customers.findCustomer(b))).isEmpty();
    }

    @Test
    void aTenantSeesOnlyItsOwnRows() {
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            for (String table : List.of("customer WHERE id", "customer_phone WHERE customer_id",
                    "credit_account WHERE customer_id", "credit_ledger_entry WHERE customer_id")) {
                assertThat(app.jdbc().sql("SELECT count(*) FROM customers." + table + " = ?").param(b)
                        .query(Long.class).single()).as(table).isZero();
                assertThat(app.jdbc().sql("SELECT count(*) FROM customers." + table + " = ?").param(a)
                        .query(Long.class).single()).as(table).isPositive();
            }
        }
    }

    @Test
    void aRowWithAnotherTenantsIdIsRefused() {
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            AppRoleSession.assertRefused(() -> app.jdbc().sql("INSERT INTO customers.customer (id, tenant_id, name, "
                    + "active, created_at, updated_at, version) VALUES (?, ?, 'Forged', true, now(), now(), 0)")
                    .param(UUID.randomUUID()).param(B).update(), StoreScopeViolations.Kind.TENANT_WRITE_REFUSED);
        }
    }

    @Test
    void aRowNeverMovesToAnotherTenant() {
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            AppRoleSession.assertRefused(() -> app.jdbc().sql("UPDATE customers.customer SET tenant_id = ? "
                    + "WHERE id = ?").param(B).param(a).update(), StoreScopeViolations.Kind.STORE_CHANGE);
            AppRoleSession.assertRefused(() -> app.jdbc().sql("UPDATE customers.credit_account SET tenant_id = ? "
                    + "WHERE customer_id = ?").param(B).param(a).update(), StoreScopeViolations.Kind.STORE_CHANGE);
        }
    }

    @Test
    void aChildCannotReferenceAnotherTenantsParent() {
        try (AppRoleSession app = AppRoleSession.open().tenant(A).allStores()) {
            assertForeignKeyViolation(() -> app.jdbc().sql("INSERT INTO customers.customer_phone (id, customer_id, "
                    + "digits, is_primary) VALUES (?, ?, '849999999', false)").param(UUID.randomUUID()).param(b)
                    .update());
            assertForeignKeyViolation(() -> app.jdbc().sql("INSERT INTO customers.customer_address (id, customer_id, "
                    + "kind, line1, version) VALUES (?, ?, 'BILLING', 'Rua 1', 0)").param(UUID.randomUUID())
                    .param(b).update());
            assertForeignKeyViolation(() -> app.jdbc().sql("INSERT INTO customers.credit_account (customer_id, "
                    + "credit_limit, currency, credit_blocked, version, created_at) "
                    + "VALUES (?, 1, 'MZN', false, 0, now())").param(b).update());
            assertForeignKeyViolation(() -> app.jdbc().sql("INSERT INTO customers.credit_limit_change (id, "
                    + "customer_id, new_limit, reason, actor_kind, actor_id, at) VALUES (?, ?, 1, 'x', 'ACCOUNT', ?, "
                    + "now())").param(UUID.randomUUID()).param(b).param(UUID.randomUUID()).update());
        }
    }

    private static void assertForeignKeyViolation(org.assertj.core.api.ThrowableAssert.ThrowingCallable insert) {
        assertThatThrownBy(insert).isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(error -> assertThat(AppRoleSession.sqlState(error)).isEqualTo("23503"));
    }

    @Test
    void anUnboundReadFailsClosed() {
        try (AppRoleSession app = AppRoleSession.open().noTenant().allStores()) {
            for (String table : List.of("customer", "customer_phone", "customer_address", "credit_account",
                    "credit_limit_change", "credit_reservation", "credit_ledger_entry", "credit_settlement")) {
                AppRoleSession.assertRefused(() -> app.jdbc().sql("SELECT count(*) FROM customers." + table)
                        .query(Long.class).single(), StoreScopeViolations.Kind.TENANT_MISSING);
            }
        }
    }

    @Test
    void theModuleIsTenantProtected() {
        RowLevelSecurityCoverageTest.assertModuleTenantProtected("customers");
    }

    /** A POS employee of {@code tenant} on a terminal of {@code store}. */
    private static RequestPostProcessor atPos(UUID tenant, UUID store, Permission... permissions) {
        Set<String> authorities = Arrays.stream(permissions).map(p -> p.authority(store)).collect(Collectors.toSet());
        UUID terminal = UUID.randomUUID();
        Actor actor = Actor.employee(UUID.randomUUID(), terminal, store, authorities);
        UsernamePasswordAuthenticationToken token = UsernamePasswordAuthenticationToken.authenticated(actor, null,
                authorities.stream().map(SimpleGrantedAuthority::new).toList());
        token.setDetails(new TenantContext(tenant, MembershipKind.TERMINAL, terminal, "test:isolation"));
        return authentication(token);
    }

    /** An account of {@code tenant} (its resolved membership as the principal's details), in a store or everywhere. */
    private static RequestPostProcessor in(UUID tenant, UUID store, Permission... permissions) {
        Set<String> authorities = Arrays.stream(permissions)
                .map(p -> store == null ? p.authority() : p.authority(store)).collect(Collectors.toSet());
        Actor actor = Actor.account(ACCOUNT, authorities);
        UsernamePasswordAuthenticationToken token = UsernamePasswordAuthenticationToken.authenticated(actor, null,
                authorities.stream().map(SimpleGrantedAuthority::new).toList());
        token.setDetails(new TenantContext(tenant, MembershipKind.ACCOUNT, actor.userId(), "test:isolation"));
        return authentication(token);
    }
}
