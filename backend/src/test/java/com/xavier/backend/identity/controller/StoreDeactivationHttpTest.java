package com.xavier.backend.identity.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Actor;
import com.xavier.backend.access.Permission;
import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.identity.IdentityFixtures.Employee;
import com.xavier.backend.identity.IdentityFixtures.Terminal;
import com.xavier.backend.identity.IdentityTest;
import com.xavier.backend.organization.model.event.StoreDeactivated;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** D45: a deactivated store stops trading on the POS at once, and its POS sessions end. */
@IdentityTest
class StoreDeactivationHttpTest {

    static final String PIN = "4821";
    static final String POS_ROUTE = "/api/v1/pos/notifications";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    PlatformTransactionManager transactions;

    IdentityFixtures fixtures;
    UUID store;

    @BeforeEach
    void store() throws Exception {
        fixtures = new IdentityFixtures(mvc);
        store = fixtures.store();
    }

    /**
     * The real organization {@code StoreActivity} behind {@code requirePos}: the actor here is not a stored session,
     * so no listener can end it; only the store lookup refuses it.
     */
    @Test
    void aPosRequestForAStoreDeactivatedThroughTheBackOfficeIsRefusedEvenWithoutTheListener() throws Exception {
        RequestPostProcessor posActor = posActorIn(store);
        mvc.perform(get(POS_ROUTE).with(posActor)).andExpect(status().isOk());

        fixtures.deactivateStore(store);

        mvc.perform(get(POS_ROUTE).with(posActor)).andExpect(status().isForbidden());
    }

    @Test
    void deactivatingAStoreEndsThePosSessionsOnAllItsTerminalsAndNoOthers() throws Exception {
        Terminal bar = fixtures.enrolledTerminal(store);
        Terminal kitchen = fixtures.enrolledTerminal(store);
        UUID otherStore = fixtures.store();
        Terminal elsewhere = fixtures.enrolledTerminal(otherStore);
        Employee employee = fixtures.employee(PIN, store, IdentityFixtures.WAITER);
        Employee other = fixtures.employee(PIN, otherStore, IdentityFixtures.WAITER);
        Cookie onBar = fixtures.posLogin(bar, employee, PIN);
        Cookie onKitchen = fixtures.posLogin(kitchen, employee, PIN);
        Cookie inOtherStore = fixtures.posLogin(elsewhere, other, PIN);

        fixtures.deactivateStore(store);

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            mvc.perform(get("/api/v1/auth/session").cookie(onBar)).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/v1/auth/session").cookie(onKitchen)).andExpect(status().isUnauthorized());
        });
        mvc.perform(get("/api/v1/auth/session").cookie(inOtherStore)).andExpect(status().isOk());
    }

    @Test
    void aRepeatedStoreDeactivatedEventIsHarmless() throws Exception {
        Terminal terminal = fixtures.enrolledTerminal(store);
        Cookie session = fixtures.posLogin(terminal, fixtures.employee(PIN, store, IdentityFixtures.WAITER), PIN);
        fixtures.deactivateStore(store);
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                mvc.perform(get("/api/v1/auth/session").cookie(session)).andExpect(status().isUnauthorized()));

        new TransactionTemplate(transactions).executeWithoutResult(tx ->
                events.publishEvent(new StoreDeactivated(store, Instant.now())));

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(identityDeliveriesOf(store)).as("both deliveries handled by identity")
                        .containsExactly(true, true));
    }

    /** Per delivery of this store's StoreDeactivated to an identity listener: completed? */
    private List<Boolean> identityDeliveriesOf(UUID storeId) {
        return jdbc.sql("""
                        SELECT completion_date IS NOT NULL FROM event_publication
                         WHERE event_type = :type AND listener_id LIKE '%.identity.%' AND serialized_event LIKE :store""")
                .param("type", StoreDeactivated.class.getName()).param("store", "%" + storeId + "%")
                .query(Boolean.class).list();
    }

    private static RequestPostProcessor posActorIn(UUID storeId) {
        Actor actor = Actor.employee(UUID.randomUUID(), UUID.randomUUID(), storeId,
                Set.of(Permission.DINING_VIEW.authority(storeId)));
        return authentication(UsernamePasswordAuthenticationToken.authenticated(actor, null,
                actor.authorities().stream().map(SimpleGrantedAuthority::new).toList()));
    }
}
