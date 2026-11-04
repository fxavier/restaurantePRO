package com.xavier.backend.organization.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.organization.OrganizationModuleTest;
import com.xavier.backend.organization.model.event.CompanyBootstrapped;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.test.AssertablePublishedEvents;
import org.springframework.transaction.support.TransactionTemplate;

@OrganizationModuleTest
class CompanyBootstrapTest {

    static final CompanyBootstrapProperties SOL = new CompanyBootstrapProperties("Restaurante Sol", "400123456", "MZ", "MZN");

    @Autowired
    CompanyBootstrapService bootstrap;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    EntityManager entityManager;

    @Test
    void createsTheCompanyOnceAndPublishesCompanyBootstrapped(AssertablePublishedEvents events) {
        inRolledBackTransactionWithoutCompany(() -> {
            assertThat(bootstrap.bootstrap(SOL)).isTrue();
            entityManager.flush();
            assertThat(bootstrap.bootstrap(new CompanyBootstrapProperties("Other", "1", "PT", "EUR"))).isFalse();

            assertThat(jdbc.sql("SELECT name FROM organization.company_profile").query(String.class).list())
                    .containsExactly("Restaurante Sol");
            assertThat(events.ofType(CompanyBootstrapped.class)).hasSize(1);
            assertThat(events.ofType(CompanyBootstrapped.class).matching(e -> e.currency().equals("MZN")))
                    .hasSize(1);
        });
    }

    @Test
    void aRestartWithACompanyAlreadyThereChangesNothingAndPublishesNothing(AssertablePublishedEvents events) {
        // the context started with bootstrap settings, so the company exists here
        String before = jdbc.sql("SELECT name || version FROM organization.company_profile").query(String.class).single();

        assertThat(bootstrap.bootstrap(new CompanyBootstrapProperties("Other", "1", "PT", "EUR"))).isFalse();

        assertThat(jdbc.sql("SELECT name || version FROM organization.company_profile").query(String.class).single())
                .isEqualTo(before);
        assertThat(events.ofType(CompanyBootstrapped.class)).isEmpty();
    }

    @Test
    void theStartupRunnerBootstrapsFromTheConfiguredSettings(@Autowired CompanyBootstrapRunner runner) {
        inRolledBackTransactionWithoutCompany(() -> {
            assertThatCode(() -> runner.run(null)).doesNotThrowAnyException();
            entityManager.flush();

            assertThat(jdbc.sql("SELECT name, tax_id, country, currency FROM organization.company_profile")
                    .query().singleRow())
                    .containsEntry("name", "Test Company").containsEntry("tax_id", "TEST-TAX-ID")
                    .containsEntry("country", "MZ").containsEntry("currency", "MZN");
        });
    }

    @Test
    void theBootstrapIsAudited() {
        inRolledBackTransactionWithoutCompany(() -> {
            bootstrap.bootstrap(SOL);
            entityManager.flush();

            assertThat(jdbc.sql("""
                            SELECT count(*) FROM audit.audit_event
                            WHERE action = 'COMPANY_BOOTSTRAPPED' AND entity_type = 'CompanyProfile'
                              AND actor_id IS NULL AND details->>'currency' = 'MZN'
                              AND occurred_at >= (SELECT created_at FROM organization.company_profile)
                            """).query(Long.class).single()).isEqualTo(1);
        });
    }

    @ParameterizedTest(name = "currency {0}")
    @ValueSource(strings = {"XXX", "ZZZ", "mzn"})
    void anUnusableCurrencyStopsTheStartupWithAClearError(String currency) {
        inRolledBackTransactionWithoutCompany(() -> assertThatThrownBy(
                () -> bootstrap.bootstrap(new CompanyBootstrapProperties("Sol", "400123456", "MZ", currency)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("restaurantpro.bootstrap.company")
                .hasMessageContaining("currency '" + currency + "'"));
    }

    @Test
    void incompleteSettingsStopTheStartup() {
        inRolledBackTransactionWithoutCompany(() -> assertThatThrownBy(
                () -> bootstrap.bootstrap(new CompanyBootstrapProperties("Sol", null, "MZ", "MZN")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("taxId is required"));
    }

    @Test
    void withoutSettingsNoCompanyIsCreated(AssertablePublishedEvents events) {
        inRolledBackTransactionWithoutCompany(() -> {
            assertThat(bootstrap.bootstrap(new CompanyBootstrapProperties(null, "", " ", null))).isFalse();

            assertThat(jdbc.sql("SELECT count(*) FROM organization.company_profile").query(Long.class).single())
                    .isZero();
            assertThat(events.ofType(CompanyBootstrapped.class)).isEmpty();
        });
    }

    @Test
    void theDatabaseHoldsAtMostOneCompany() {
        // criterion §7.1: no endpoint creates a company, and the table itself refuses a second one
        assertThatThrownBy(() -> insertCompany("TRUE")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertCompany("FALSE")).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM organization.company_profile").query(Long.class).single())
                .isEqualTo(1);
    }

    private void insertCompany(String id) {
        jdbc.sql("INSERT INTO organization.company_profile (id, name, tax_id, country, currency, version, created_at, "
                + "updated_at) VALUES (" + id + ", 'Second', 'X', 'PT', 'EUR', 0, now(), now())").update();
    }

    /** Each test deletes the company inside a transaction that rolls back, so other tests still see it. */
    private void inRolledBackTransactionWithoutCompany(Runnable body) {
        transaction.executeWithoutResult(tx -> {
            jdbc.sql("DELETE FROM organization.company_profile").update();
            body.run();
            tx.setRollbackOnly();
        });
    }
}
