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
class TransactionsPerCommandTest {

    static final String POS = "/api/v1/pos";
    static final String PIN = "4826";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    MeterRegistry meters;

    TransactionCount count;
    UUID store;
    UUID item;
    UUID table;
    long catalogVersion;
    RequestPostProcessor waiter;

    @BeforeEach
    void aWaiterOnAnEnrolledTerminalOfASellingStore() throws Exception {
        count = new TransactionCount(meters);
        Publications publications = new Publications(mvc, jdbc);
        Publications.SellableStore sellable = publications.sellableStore();
        store = sellable.store();
        item = UUID.fromString(sellable.item().get("id").asString());
        publications.publishNew(store);
        UUID area = created(post(DiningFixtures.AREAS).with(Actors.inStore(store, Permission.DINING_MANAGE))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(DiningFixtures.newArea(store, "SALA")));
        table = created(post(DiningFixtures.TABLES).with(Actors.inStore(store, Permission.DINING_MANAGE))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(DiningFixtures.newTable(store, area, "M1", 4)));
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
        UUID order = openedOrder();
        UUID line = id(json(mvc.perform(addLine(order)).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString()));
        awaitDeliveries(OffsetDateTime.MIN);
        OffsetDateTime start = OwnerDatabase.shared().jdbc().sql("SELECT clock_timestamp()")
                .query(OffsetDateTime.class).single();
        Map<String, Long> before = count.snapshot();

        mvc.perform(post(POS + "/orders/" + order + "/batches").with(waiter).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"orderVersion": 0, "catalogVersion": %d, "lineIds": ["%s"]}"""
                                .formatted(catalogVersion, line)))
                .andExpect(status().isCreated());
        long invocations = awaitDeliveries(start);
        Map<String, Long> begun = count.since(before);

        assertThat(invocations).as("the round's listener invocations").isPositive();
        assertThat(begun.getOrDefault("registry", 0L)).as("markProcessing and markCompleted, per invocation: %s",
                begun).isEqualTo(2 * invocations);
        assertThat(begun.getOrDefault("listener", 0L) + begun.getOrDefault("registry", 0L))
                .as("per invocation: %s", begun).isEqualTo(3 * invocations);
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
        return created(post(POS + "/tables/" + table + "/open").with(waiter).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"guests\": 2}"));
    }

    /**
     * Waits until every event publication since {@code since} is completed and none is added any more.
     *
     * @return how many there were (one per listener invocation)
     */
    private long awaitDeliveries(OffsetDateTime since) {
        JdbcClient owner = OwnerDatabase.shared().jdbc();
        long[] seen = {-1};
        Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).until(() -> {
            Map<String, Object> row = owner.sql("""
                            SELECT count(*) AS total, count(*) FILTER (WHERE completion_date IS NULL) AS open
                            FROM public.event_publication WHERE publication_date >= ?""").param(since)
                    .query().singleRow();
            long total = ((Number) row.get("total")).longValue();
            boolean settled = ((Number) row.get("open")).longValue() == 0 && total == seen[0];
            seen[0] = total;
            return settled;
        });
        return seen[0];
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
