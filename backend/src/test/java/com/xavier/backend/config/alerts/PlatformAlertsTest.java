package com.xavier.backend.config.alerts;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

import com.xavier.backend.access.Permission;
import com.xavier.backend.config.security.Members;
import com.xavier.backend.config.security.SecurityHttpTest;
import com.xavier.backend.config.security.TestJwtFactory;
import com.xavier.backend.shared.ops.AlertSeverity;
import com.xavier.backend.shared.ops.AlertSource;
import com.xavier.backend.shared.ops.OperationalAlert;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.util.ClassUtils;

/**
 * User decision (R6): a tenant sees only its own alerts. A platform condition (disk, backups, the event registry,
 * row-level security) never reaches a tenant user, however wide their SYSTEM_MONITOR; a platform operator lists it at
 * {@code /api/v1/platform/alerts}.
 */
@SecurityHttpTest
@Import(PlatformAlertsTest.PlatformTestSource.Config.class)
class PlatformAlertsTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationContext context;

    @Test
    void aTenantAdministratorWithSystemMonitorNeverSeesAPlatformAlert() throws Exception {
        UUID tenant = Members.activeTenant();
        String administrator = "kc-" + UUID.randomUUID();
        Members.member(tenant, administrator, Members.role(tenant, Permission.SYSTEM_MONITOR));

        mvc.perform(get(AlertsController.PATH).with(TestJwtFactory.account(administrator).bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts[*].code").value(not(hasItem(PlatformTestSource.CODE))));
    }

    @Test
    void aPlatformOperatorListsThePlatformAlerts() throws Exception {
        mvc.perform(get("/api/v1/platform/alerts").with(operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts[*].code").value(hasItem(PlatformTestSource.CODE)));
    }

    @Test
    void aTenantUserCannotListThePlatformAlerts() throws Exception {
        UUID tenant = Members.activeTenant();
        String administrator = "kc-" + UUID.randomUUID();
        Members.member(tenant, administrator, Members.role(tenant, Permission.SYSTEM_MONITOR));

        mvc.perform(get("/api/v1/platform/alerts").with(TestJwtFactory.account(administrator).bearer()))
                .andExpect(status().isForbidden());
    }

    /** The real sources: the platform's conditions are PLATFORM, a tenant's own data TENANT. */
    @Test
    void eachRealSourceIsClassifiedForItsAudience() {
        Map<String, AlertSource.Scope> scopes = new TreeMap<>();
        context.getBeansOfType(AlertSource.class).values().stream()
                .filter(source -> !(source instanceof PlatformTestSource))
                .forEach(source -> scopes.put(ClassUtils.getUserClass(source).getSimpleName(), source.scope()));

        assertThat(scopes).containsExactlyInAnyOrderEntriesOf(Map.of(
                "BackupAlertSource", AlertSource.Scope.PLATFORM,
                "DiskAlertSource", AlertSource.Scope.PLATFORM,
                "EventPublicationAlertSource", AlertSource.Scope.PLATFORM,
                "StoreScopeAlertSource", AlertSource.Scope.PLATFORM,
                "CashDiscrepancyAlertSource", AlertSource.Scope.TENANT,
                "InDoubtAlertSource", AlertSource.Scope.TENANT,
                "PrintQueueAlertSource", AlertSource.Scope.TENANT,
                "SupportAccessAlerts", AlertSource.Scope.TENANT,
                "ExportMonitor", AlertSource.Scope.TENANT));
    }

    private static RequestPostProcessor operator() {
        return TestJwtFactory.support("operator-" + UUID.randomUUID(), Instant.now())
                .claim("realm_access", Map.of("roles", List.of("platform-operator"))).bearer();
    }

    /** A platform condition, as the disk or backup sources report one. */
    static final class PlatformTestSource implements AlertSource {

        static final String CODE = "TEST_PLATFORM_CONDITION";

        @Override
        public Scope scope() {
            return Scope.PLATFORM;
        }

        @Override
        public List<OperationalAlert> alerts() {
            return List.of(new OperationalAlert(CODE, AlertSeverity.CRITICAL, null, 1,
                    Instant.parse("2026-11-17T08:00:00Z"), "A platform condition"));
        }

        @TestConfiguration(proxyBeanMethods = false)
        static class Config {

            @Bean
            AlertSource platformTestSource() {
                return new PlatformTestSource();
            }
        }
    }
}
