package com.xavier.backend.config.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.access.Permission;
import com.xavier.backend.catalog.Actors;
import com.xavier.backend.catalog.Publications;
import com.xavier.backend.config.security.DeviceHttpTest;
import com.xavier.backend.dining.DiningFixtures;
import com.xavier.backend.identity.IdentityFixtures;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;

/**
 * S77 (A92, F5): the transactions one POS request begins, through the real security chain (the terminal's token and
 * {@code X-Pos-Session}), counted by {@code db_transactions} under origin {@code request} (authentication, the use case,
 * idempotency). Event delivery runs after the response on other threads ({@code listener}, {@code registry}): a batch's
 * is pinned per listener invocation, Spring Modulith's own cost. Authentication of a POS request is two:
 * {@code TenancyApi.resolve} and {@code PrincipalActors.posSession} (which also answers {@code MustChangePinFilter}).
 */
@DeviceHttpTest
@org.springframework.test.context.TestPropertySource(properties = "restaurantpro.test.own-context=transactions-per-command")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class TransactionsPerCommandTest {

    static final String POS = "/api/v1/pos";
    static final String PIN = "4826";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    MeterRegistry meters;

    @Autowired
    java.util.List<org.springframework.scheduling.config.ScheduledTaskHolder> scheduled;
    @Autowired
    org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler scheduler;

    /** The stores this class created: their deliveries are the only ones this context runs. */
    static final java.util.Set<UUID> STORES = java.util.concurrent.ConcurrentHashMap.newKeySet();

    TransactionCount count;
    UUID store;
    UUID item;
    UUID area;
    UUID table;
    long catalogVersion;
    RequestPostProcessor waiter;

    @BeforeEach
    void aWaiterOnAnEnrolledTerminalOfASellingStore() throws Exception {
        stopThisContextsScheduler();
        count = new TransactionCount(meters);
        Publications publications = new Publications(mvc, jdbc);
        Publications.SellableStore sellable = publications.sellableStore();
        store = sellable.store();
        STORES.add(store);
        item = UUID.fromString(sellable.item().get("id").asString());
        publications.publishNew(store);
        area = created(post(DiningFixtures.AREAS).with(Actors.inStore(store, Permission.DINING_MANAGE))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(DiningFixtures.newArea(store, "SALA")));
        table = newTable("M1");
        IdentityFixtures identity = new IdentityFixtures(mvc);
        waiter = identity.posLogin(identity.enrolledTerminal(store),
                identity.employee(PIN, store, IdentityFixtures.WAITER), PIN);
        catalogVersion = json(mvc.perform(get(POS + "/catalog").with(waiter)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("version").asLong();
    }

    @Test
    void readingTheTablesOrTheCatalogBeginsAtMostThreeTransactions() throws Exception {
        assertThat(requestTransactions(get(POS + "/tables?storeId=" + store).with(waiter), 200))
                .as("authentication 2, the read 1").isLessThanOrEqualTo(3);
        assertThat(requestTransactions(get(POS + "/catalog").with(waiter), 200))
                .as("authentication 2, the read 1").isLessThanOrEqualTo(3);
    }

    @Test
    void addingALineBeginsAtMostFourTransactions() throws Exception {
        UUID order = openedOrder();

        long begun = requestTransactions(addLine(order), 201);

        assertThat(begun).as("authentication 2, the idempotency claim (and its read) 1, the action 1")
                .isLessThanOrEqualTo(4);
    }

    @Test
    void aConfirmedRoundsEventDeliveryCostsThreeTransactionsPerListenerInvocation() throws Exception {
        // the shared table holds what other tests left: a publication that failed and is never completed
        UUID foreign = UUID.randomUUID();
        OwnerDatabase.shared().jdbc().sql("""
                        INSERT INTO public.event_publication (id, listener_id, event_type, serialized_event,
                                                              publication_date, status, completion_attempts)
                        VALUES (?, 'another.Test.listener', 'another.Event', '{}', now(), 'FAILED', 1)""")
                .param(foreign).update();
        // ... and other contexts keep moving theirs: the foreign row is resubmitted every 100 ms meanwhile
        java.util.concurrent.ScheduledExecutorService churn = java.util.concurrent.Executors
                .newSingleThreadScheduledExecutor();
        churn.scheduleWithFixedDelay(() -> OwnerDatabase.shared().jdbc().sql(
                        "UPDATE public.event_publication SET last_resubmission_date = now() WHERE id = ?")
                .param(foreign).update(), 0, 100, java.util.concurrent.TimeUnit.MILLISECONDS);
        try {
            measureOneRound();
        } finally {
            churn.shutdownNow();
            OwnerDatabase.shared().jdbc().sql("DELETE FROM public.event_publication WHERE id = ?").param(foreign)
                    .update();
        }
    }

    /** One confirmed round: its listener and registry transactions, on this context's counter. */
    private void measureOneRound() throws Exception {
        UUID order = openedOrder();
        UUID line = id(json(mvc.perform(addLine(order)).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString()));
        awaitDeliveries(OffsetDateTime.MIN, STORES);
        OffsetDateTime start = OwnerDatabase.shared().jdbc().sql("SELECT clock_timestamp()")
                .query(OffsetDateTime.class).single();
        Map<String, Long> before = count.snapshot();

        mvc.perform(post(POS + "/orders/" + order + "/batches").with(waiter).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"orderVersion": 0, "catalogVersion": %d, "lineIds": ["%s"]}"""
                                .formatted(catalogVersion, line)))
                .andExpect(status().isCreated());
        long invocations = awaitDeliveries(start, java.util.Set.of(store));
        Map<String, Long> begun = count.since(before);

        assertThat(invocations).as("the round's listener invocations").isPositive();
        assertThat(begun.getOrDefault("registry", 0L)).as("markProcessing and markCompleted per invocation, "
                + "%d invocations: %s", invocations, begun).isEqualTo(2 * invocations);
        assertThat(begun.getOrDefault("listener", 0L) + begun.getOrDefault("registry", 0L))
                .as("per invocation, %d invocations: %s", invocations, begun).isEqualTo(3 * invocations);
    }

    /** This context's jobs would begin transactions on its counter at any moment: none runs from now on. */
    private void stopThisContextsScheduler() {
        scheduled.forEach(holder -> holder.getScheduledTasks().forEach(task -> task.cancel(false)));
        Awaitility.await().atMost(Duration.ofSeconds(60))
                .until(() -> scheduler.getScheduledThreadPoolExecutor().getActiveCount() == 0);
    }

    /** The transactions begun under origin {@code request} while {@code request} was served. */
    private long requestTransactions(RequestBuilder request, int expectedStatus) throws Exception {
        Map<String, Long> before = count.snapshot();
        mvc.perform(request).andExpect(status().is(expectedStatus));
        return count.since(before).getOrDefault("request", 0L);
    }

    private RequestBuilder addLine(UUID order) {
        return post(POS + "/orders/" + order + "/lines").with(waiter).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"itemId": "%s", "quantity": 1}""".formatted(item));
    }

    private UUID openedOrder() throws Exception {
        return openedOrder(table);
    }

    private UUID openedOrder(UUID at) throws Exception {
        return created(post(POS + "/tables/" + at + "/open").with(waiter).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"guests\": 2}"));
    }

    /**
     * Waits until the publications since {@code since} of {@code stores} (every event carries its {@code storeId},
     * D166) are all completed and no more are added; other tests' rows are not waited on.
     *
     * @return how many there were (one per listener invocation)
     */
    private long awaitDeliveries(OffsetDateTime since, java.util.Set<UUID> stores) {
        JdbcClient owner = OwnerDatabase.shared().jdbc();
        String[] patterns = stores.stream().map(each -> "%" + each + "%").toArray(String[]::new);
        long[] seen = {-1, -1};
        try {
            Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).until(() -> {
                Map<String, Object> row = owner.sql("""
                                SELECT count(*) AS total, count(*) FILTER (WHERE completion_date IS NULL) AS open
                                FROM public.event_publication
                                WHERE publication_date >= ? AND serialized_event LIKE ANY (?)""")
                        .param(since).param(patterns).query().singleRow();
                long total = ((Number) row.get("total")).longValue();
                long open = ((Number) row.get("open")).longValue();
                boolean settled = open == 0 && total == seen[0];
                seen[0] = total;
                seen[1] = open;
                return settled;
            });
        } catch (org.awaitility.core.ConditionTimeoutException e) {
            throw new AssertionError("the publications of %s since %s did not settle: %d, %d not completed"
                    .formatted(stores, since, seen[0], seen[1]), e);
        }
        return seen[0];
    }

    private UUID newTable(String code) throws Exception {
        return created(post(DiningFixtures.TABLES).with(Actors.inStore(store, Permission.DINING_MANAGE))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(DiningFixtures.newTable(store, area, code, 4)));
    }

    private UUID created(RequestBuilder request) throws Exception {
        return id(json(mvc.perform(request).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString()));
    }

    private static UUID id(JsonNode node) {
        return UUID.fromString(node.get("id").asString());
    }

    private static JsonNode json(String body) {
        return IdentityFixtures.JSON.readTree(body);
    }
}
