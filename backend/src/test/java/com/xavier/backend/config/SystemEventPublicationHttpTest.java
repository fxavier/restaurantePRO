package com.xavier.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.WithActor;
import com.xavier.backend.shared.web.PageResponse;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * D88: the BackOffice view of incomplete event publications and a person's resubmission, both SYSTEM_MONITOR
 * (installation-wide), the resubmission audited with no store. The publications are rows written here for a listener
 * that does not exist, so a resubmission only counts as an attempt.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SystemEventPublicationHttpTest {

    static final String PATH = "/api/v1/backoffice/system/event-publications";
    static final String LISTENER = "com.xavier.backend.config.SystemEventPublicationHttpTest$Gone.on(x)";
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Clock clock;

    @Test
    @WithActor(permissions = Permission.SYSTEM_MONITOR)
    void listsFailedAndIncompletePublicationsSeparatelyWithoutTheirPayload() throws Exception {
        UUID failed = publication(10, Duration.ofMinutes(1));
        UUID retrying = publication(2, Duration.ofMinutes(1));

        Map<UUID, JsonNode> failedList = everyListed("FAILED");
        Map<UUID, JsonNode> incompleteList = everyListed("INCOMPLETE");

        assertThat(failedList).containsKey(failed).doesNotContainKey(retrying);
        assertThat(failedList.get(failed).get("state").asString()).isEqualTo("FAILED");
        assertThat(failedList.get(failed).get("completionAttempts").asInt()).isEqualTo(10);
        assertThat(failedList.values()).noneMatch(row -> row.has("serializedEvent"));
        assertThat(incompleteList).containsKey(retrying).doesNotContainKey(failed);
        assertThat(incompleteList.get(retrying).get("listenerId").asString()).isEqualTo(LISTENER);
    }

    /**
     * Every page of the list, by id: the shared test database holds every publication the suite left behind, which may
     * outgrow the 200-row page cap, so a row written here may be on any page.
     */
    private Map<UUID, JsonNode> everyListed(String state) throws Exception {
        Map<UUID, JsonNode> rows = new HashMap<>();
        long total = 1;
        for (int page = 0; (long) page * PageResponse.MAX_PAGE_SIZE < total; page++) {
            JsonNode body = JSON.readTree(mvc.perform(get(PATH).param("state", state)
                            .param("size", String.valueOf(PageResponse.MAX_PAGE_SIZE))
                            .param("page", String.valueOf(page)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            total = body.get("totalElements").asLong();
            body.get("content").forEach(row -> rows.put(UUID.fromString(row.get("id").asString()), row));
        }
        return rows;
    }

    @Test
    @WithActor(permissions = Permission.SYSTEM_MONITOR)
    void aResubmissionByIdsIsAcceptedCountedAndAuditedWithoutAStore() throws Exception {
        UUID failed = publication(10, Duration.ofMinutes(1));

        resubmit("""
                {"publicationIds": ["%s", "%s"]}""".formatted(failed, UUID.randomUUID()))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.selected").value(1));

        assertThat(attempts(failed)).isEqualTo(11); // a person may retry past the automatic limit
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM audit.audit_event WHERE action = 'EVENT_PUBLICATIONS_RESUBMITTED'
                        AND store_id IS NULL AND actor_id = :actor AND details::text LIKE :id""")
                .param("actor", UUID.fromString(WithActor.DEFAULT_USER_ID)).param("id", "%" + failed + "%")
                .query(Long.class).single()).isOne();
    }

    @Test
    @WithActor(permissions = Permission.SYSTEM_MONITOR)
    void aResubmissionOlderThanAnAgeTakesOnlyOlderPublications() throws Exception {
        UUID old = publication(10, Duration.ofDays(400));
        UUID recent = publication(10, Duration.ofMinutes(1));

        resubmit("""
                {"olderThan": "P365D"}""").andExpect(status().isAccepted())
                .andExpect(jsonPath("$.selected").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

        assertThat(attempts(old)).isEqualTo(11);
        assertThat(attempts(recent)).isEqualTo(10);
    }

    @Test
    @WithActor(permissions = Permission.SYSTEM_MONITOR)
    void aResubmissionNamesEitherIdsOrAnAgeAndNotBoth() throws Exception {
        resubmit("{}").andExpect(status().isBadRequest());
        resubmit("""
                {"publicationIds": ["%s"], "olderThan": "PT1H"}""".formatted(UUID.randomUUID()))
                .andExpect(status().isBadRequest());
        resubmit("""
                {"olderThan": "-PT1H"}""").andExpect(status().isBadRequest());
        list("SOMETHING").andExpect(status().isBadRequest());
    }

    @Test
    @WithActor(permissions = Permission.AUDIT_VIEW)
    void bothNeedSystemMonitor() throws Exception {
        list("FAILED").andExpect(status().isForbidden());
        resubmit("""
                {"olderThan": "PT1H"}""").andExpect(status().isForbidden());
    }

    @Test
    @WithActor(permissions = Permission.SYSTEM_MONITOR, storeId = "0190a000-0000-7000-8000-0000000000c1")
    void aStoreScopedSystemMonitorIsNotEnough() throws Exception {
        list("FAILED").andExpect(status().isForbidden());
    }

    private UUID publication(int attempts, Duration age) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO event_publication (id, listener_id, event_type, serialized_event, publication_date,
                                                       status, completion_attempts)
                        VALUES (:id, :listener, :type, :event, :at, 'FAILED', :attempts)""")
                .param("id", id).param("listener", LISTENER).param("type", Gone.class.getName())
                .param("event", "{\"id\":\"" + id + "\"}").param("at", Timestamp.from(clock.instant().minus(age)))
                .param("attempts", attempts).update();
        return id;
    }

    private int attempts(UUID id) {
        return jdbc.sql("SELECT completion_attempts FROM event_publication WHERE id = :id").param("id", id)
                .query(Integer.class).single();
    }

    private ResultActions list(String state) throws Exception {
        return mvc.perform(get(PATH).param("state", state).param("size", "200"));
    }

    private ResultActions resubmit(String body) throws Exception {
        return mvc.perform(post(PATH + "/resubmit").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** An event whose listener no longer exists. */
    public record Gone(UUID id) {
    }
}
