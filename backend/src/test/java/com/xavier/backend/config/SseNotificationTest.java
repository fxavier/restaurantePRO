package com.xavier.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.StoreActivity;
import com.xavier.backend.access.WithActor;
import com.xavier.backend.shared.notify.ChangeNotifier;
import com.xavier.backend.shared.notify.ChangeTopic;
import java.nio.charset.StandardCharsets; import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D60 / A14: a POS client subscribed to its store's change stream receives {@code {topic, id}} notices, only after
 * the writing transaction commits, never after a rollback, never another store's, and never any order data.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, SseNotificationTest.AllStoresActive.class})
class SseNotificationTest {

    static final String STORE = "0190a000-0000-7000-8000-0000000000a1";
    static final String OTHER_STORE = "0190a000-0000-7000-8000-0000000000a2";
    static final String EMPLOYEE = "0190e000-0000-7000-8000-0000000000e1";
    static final String TERMINAL = "0190f000-0000-7000-8000-0000000000f1";
    static final String PATH = "/api/v1/pos/notifications";

    @Autowired
    MockMvc mvc;

    @Autowired
    ChangeNotifier notifier;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    @WithActor(permissions = Permission.DINING_VIEW, storeId = STORE, employeeId = EMPLOYEE, terminalId = TERMINAL)
    void aNoticeArrivesAfterCommitAndNoneAfterARollback() throws Exception {
        MockHttpServletResponse stream = subscribe();
        UUID committed = UUID.randomUUID();
        UUID rolledBack = UUID.randomUUID();
        UUID otherStore = UUID.randomUUID();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        tx.executeWithoutResult(status -> {
            notifier.notifyAfterCommit(UUID.fromString(STORE), ChangeTopic.ORDER, rolledBack);
            status.setRollbackOnly();
        });
        tx.executeWithoutResult(status -> {
            notifier.notifyAfterCommit(UUID.fromString(OTHER_STORE), ChangeTopic.ORDER, otherStore);
            notifier.notifyAfterCommit(UUID.fromString(STORE), ChangeTopic.ORDER, committed);
            assertThat(new String(stream.getContentAsByteArray(), StandardCharsets.UTF_8)).doesNotContain(committed.toString()); // not before the commit
        });

        eventually(() -> assertThat(new String(stream.getContentAsByteArray(), StandardCharsets.UTF_8))
                .contains("\"topic\":\"ORDER\"").contains("\"id\":\"" + committed + "\""));
        assertThat(new String(stream.getContentAsByteArray(), StandardCharsets.UTF_8)).doesNotContain(rolledBack.toString())
                .doesNotContain(otherStore.toString());
    }

    @Test
    @WithActor(permissions = Permission.PRODUCTION_VIEW, storeId = STORE, employeeId = EMPLOYEE,
            terminalId = TERMINAL)
    void theKitchenSubscribesWithProductionViewAndANoticeCarriesOnlyTopicAndId() throws Exception {
        MockHttpServletResponse stream = subscribe();

        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                notifier.notifyAfterCommit(UUID.fromString(STORE), ChangeTopic.TABLES, null));

        eventually(() -> assertThat(new String(stream.getContentAsByteArray(), StandardCharsets.UTF_8))
                .contains("data:{\"topic\":\"TABLES\"}"));
    }

    @Test
    void notifyingOutsideATransactionIsAProgrammingError() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        notifier.notifyAfterCommit(UUID.fromString(STORE), ChangeTopic.ORDER, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @WithActor(permissions = Permission.DINING_VIEW)
    void aBackOfficeSessionCannotSubscribe() throws Exception {
        mvc.perform(get(PATH).accept(MediaType.TEXT_EVENT_STREAM)).andExpect(status().isForbidden());
    }

    @Test
    @WithActor(permissions = Permission.CATALOG_VIEW, storeId = STORE, employeeId = EMPLOYEE, terminalId = TERMINAL)
    void aPosSessionWithoutDiningOrProductionViewCannotSubscribe() throws Exception {
        mvc.perform(get(PATH).accept(MediaType.TEXT_EVENT_STREAM)).andExpect(status().isForbidden());
    }

    @Test
    void anAnonymousRequestCannotSubscribe() throws Exception {
        mvc.perform(get(PATH).accept(MediaType.TEXT_EVENT_STREAM)).andExpect(status().isUnauthorized());
    }

    /** Notices are sent off the committing thread; poll briefly (no Awaitility on the classpath). */
    private static void eventually(Runnable assertion) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (true) {
            try {
                assertion.run();
                return;
            } catch (AssertionError e) {
                if (System.nanoTime() > deadline) {
                    throw e;
                }
                Thread.sleep(20);
            }
        }
    }

    private MockHttpServletResponse subscribe() throws Exception {
        MvcResult result = mvc.perform(get(PATH).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
        return result.getResponse();
    }

    /** The session's store must be active for {@code requirePos}; these test stores exist only in the session. */
    @TestConfiguration(proxyBeanMethods = false)
    static class AllStoresActive {
        @Bean
        @Primary
        StoreActivity allStoresActive() {
            return storeId -> true;
        }
    }
}
