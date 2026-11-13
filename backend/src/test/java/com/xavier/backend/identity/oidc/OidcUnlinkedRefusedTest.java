package com.xavier.backend.identity.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/** A61: an identity linked to no account is refused like a wrong password, audited, and no account is created. */
@OidcTest
class OidcUnlinkedRefusedTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Test
    void anUnlinkedSubjectIsRefusedAuditedAndCreatesNoAccount() throws Exception {
        String subject = OidcFlow.newSubject();
        long accounts = jdbc.sql("SELECT count(*) FROM identity.user_account").query(Long.class).single();
        int refusals = OidcAudit.details(jdbc, "OIDC_LOGIN_REFUSED", "oidc").size();

        MockHttpServletResponse callback = new OidcFlow(mvc, OidcTest.IDP).login(subject, List.of("pwd"),
                Map.of("preferred_username", "new.person", "email", "new.person@example.com"));

        assertThat(callback.getRedirectedUrl()).isEqualTo("/backoffice/login?error=oidc");
        assertThat(jdbc.sql("SELECT count(*) FROM identity.user_account").query(Long.class).single())
                .isEqualTo(accounts);
        assertThat(jdbc.sql("SELECT count(*) FROM identity.external_identity WHERE subject = ?").param(subject)
                .query(Long.class).single()).isZero();
        List<String> unlinked = OidcAudit.details(jdbc, "OIDC_LOGIN_REFUSED", "oidc");
        assertThat(unlinked).hasSize(refusals + 1)
                .allSatisfy(details -> assertThat(details).contains(OidcTest.IDP.issuer()).contains("UNLINKED")
                        .doesNotContain(subject).doesNotContain("stub-access"));
        if (callback.getCookie("SESSION") != null) {
            mvc.perform(get("/api/v1/auth/session").cookie(callback.getCookie("SESSION")))
                    .andExpect(status().isUnauthorized());
        }
    }
}
