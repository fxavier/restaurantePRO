package com.xavier.backend.identity.oidc;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.access.Actor;
import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.identity.Races;
import com.xavier.backend.identity.oidc.exception.ExternalIdentityExistsException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * D144 "UNIQUE (issuer, subject) and one link per account", under concurrency on real PostgreSQL: every loser of a
 * race gets 409 {@code EXTERNAL_IDENTITY_EXISTS}, never a 500, and exactly one row remains.
 */
@OidcTest
class ExternalIdentityLinkRaceTest {

    static final String ISSUER = "https://race.example/realm";

    @Autowired
    MockMvc mvc;

    @Autowired
    ExternalIdentityService identities;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    JdbcClient jdbc;

    @Test
    void twoTabsOfOneAccountLinkingBehindAnUncommittedLinkOfThatAccountAllLose() throws Exception {
        UUID account = new IdentityFixtures(mvc).user(Set.of(), null).id();
        Actor actor = Actor.account(account, Set.of());

        // the test's own uncommitted link of the account holds the account_id unique index entry
        List<String> outcomes = Races.behindLock(transactions, jdbc, uncommittedLink(OidcFlow.newSubject()), account,
                List.of(link(actor, OidcFlow.newSubject()), link(actor, OidcFlow.newSubject())));

        assertThat(outcomes).containsExactly(ExternalIdentityExistsException.CODE,
                ExternalIdentityExistsException.CODE);
        assertThat(jdbc.sql("SELECT count(*) FROM identity.external_identity WHERE account_id = ?").param(account)
                .query(Long.class).single()).isOne();
    }

    @Test
    void twoAccountsLinkingTheSameIdentityBehindAnUncommittedLinkAllLose() throws Exception {
        IdentityFixtures fixtures = new IdentityFixtures(mvc);
        UUID holder = fixtures.user(Set.of(), null).id();
        UUID first = fixtures.user(Set.of(), null).id();
        UUID second = fixtures.user(Set.of(), null).id();
        String subject = OidcFlow.newSubject();

        // the test's own uncommitted link of the identity holds the (issuer, subject) unique index entry
        List<String> outcomes = Races.behindLock(transactions, jdbc, uncommittedLink(subject), holder,
                List.of(link(Actor.account(first, Set.of()), subject), link(Actor.account(second, Set.of()), subject)));

        assertThat(outcomes).containsExactly(ExternalIdentityExistsException.CODE,
                ExternalIdentityExistsException.CODE);
        assertThat(jdbc.sql("SELECT account_id FROM identity.external_identity WHERE issuer = ? AND subject = ?")
                .param(ISSUER).param(subject).query(UUID.class).list()).containsExactly(holder);
    }

    /** One parameter, the account; issuer and subject are this test's own literals. */
    private static String uncommittedLink(String subject) {
        return "WITH holder AS (SELECT CAST(? AS uuid) AS id) INSERT INTO identity.external_identity (id, account_id, "
                + "issuer, subject, linked_at, linked_by_account_id) SELECT gen_random_uuid(), id, '" + ISSUER
                + "', '" + subject + "', now(), id FROM holder RETURNING id";
    }

    private Callable<String> link(Actor actor, String subject) {
        return () -> {
            try {
                identities.link(actor, ISSUER, subject);
                return "LINKED";
            } catch (ExternalIdentityExistsException e) {
                return e.code();
            }
        };
    }

}
