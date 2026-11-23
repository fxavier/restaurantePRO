package com.xavier.backend.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.access.Permission;
import com.xavier.backend.config.security.Members;
import com.xavier.backend.config.security.SecurityHttpTest;
import com.xavier.backend.config.security.TestJwtFactory;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * D183: a support grant's window is its membership's, which the database dates (V15003). With the application's
 * clock ahead of the database's, the grant, its audit row and its membership still name one and the same window.
 */
@SecurityHttpTest
@Import(SupportGrantClockSkewTest.AheadOfTheDatabase.class)
class SupportGrantClockSkewTest {

    static final Duration AHEAD = Duration.ofSeconds(5);

    @Autowired
    MockMvc mvc;

    @Test
    void theGrantItsAuditAndItsMembershipShareTheDatabasesWindow() throws Exception {
        UUID tenant = Members.activeTenant();
        String admin = "kc-" + UUID.randomUUID();
        Members.member(tenant, admin, Members.role(tenant, Permission.SUPPORT_ACCESS_MANAGE));
        UUID supportAccount = Members.account(tenant, true, Members.role(tenant, Permission.CATALOG_MANAGE));

        String created = mvc.perform(post("/api/v1/backoffice/support-grants")
                        .with(TestJwtFactory.account(admin).bearer())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"accountId": "%s", "subject": "ops-%s", "durationMinutes": 60, "reason": "ticket"}"""
                                .formatted(supportAccount, UUID.randomUUID())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID grant = UUID.fromString(JsonMapper.builder().build().readTree(created).get("id").asString());

        assertThat(OwnerDatabase.shared().jdbc().sql("""
                        SELECT (a.details ->> 'validFrom')::timestamptz = m.valid_from
                           AND (a.details ->> 'validUntil')::timestamptz = m.valid_until
                           AND g.valid_from = m.valid_from AND g.valid_until = m.valid_until
                        FROM identity.support_grant g
                            JOIN tenancy.tenant_membership m ON m.id = g.membership_id
                            JOIN audit.audit_event a ON a.entity_id = g.id::text
                                AND a.action = 'SUPPORT_GRANT_CREATED'
                        WHERE g.id = ?""").param(grant).query(Boolean.class).single())
                .as("audit, grant and membership name the database's window").isTrue();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class AheadOfTheDatabase {

        @Bean
        @Primary
        Clock aheadOfTheDatabase() {
            // microsecond ticks like the application clock (ClockConfiguration): Linux's system clock has nanoseconds
            return Clock.tick(Clock.offset(Clock.systemUTC(), AHEAD), Duration.ofNanos(1_000));
        }
    }
}
