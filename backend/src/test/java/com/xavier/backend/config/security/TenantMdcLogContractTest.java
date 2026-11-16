package com.xavier.backend.config.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Security rule 7, D186 log contract: every log line written after the tenant is resolved carries {@code tenant_id}
 * (MDC, in the ECS JSON), and the key does not outlive the request. Here the line is {@code AccessPolicy}'s denial of
 * a member that lacks the permission.
 */
@SecurityHttpTest
@ExtendWith(OutputCaptureExtension.class)
class TenantMdcLogContractTest {

    @Autowired
    MockMvc mvc;

    @Test
    void aLineAfterResolutionCarriesTheTenantAndTheKeyEndsWithTheRequest(CapturedOutput output) throws Exception {
        UUID tenant = Members.activeTenant();
        String subject = "kc-" + UUID.randomUUID();
        UUID account = Members.member(tenant, subject);

        mvc.perform(get("/api/v1/backoffice/users").with(TestJwtFactory.account(subject).bearer()))
                .andExpect(status().isForbidden());

        assertThat(output.getOut().lines().filter(line -> line.contains("Access denied")
                        && line.contains(account.toString())))
                .isNotEmpty()
                .allSatisfy(line -> assertThat(line).contains("\"tenant_id\":\"" + tenant + "\""));
        assertThat(MDC.get("tenant_id")).isNull();
    }
}
