package com.xavier.backend.production.service;

import static com.xavier.backend.fiscal.FiscalActors.inStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.fiscal.FiscalFixtures;
import com.xavier.backend.production.ProductionFixtures;
import com.xavier.backend.production.ProductionModuleTest;
import com.xavier.backend.production.TcpFakePrinter;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * D157, A65 through the print worker: a printer that answers "not ready" gets nothing, and its job waits and retries
 * as for a refused connection (RETRY_WAIT with backoff, FAILED after the fifth attempt), with the cause as
 * {@code last_error}; "paper near end" prints and shows on the printer list. The printer's version never moves.
 */
@ProductionModuleTest
class StatusProbeNotReadyTest {

    static final byte READY = 0x12;

    @Autowired
    ProductionFixtures fixtures;

    @Autowired
    FiscalFixtures stores;

    @Autowired
    MockMvc mvc;

    @Test
    void aCoverOpenKeepsTheJobWaitingThenFailsItAfterFiveAttemptsWithNothingPrinted() throws Exception {
        UUID store = stores.newStore();
        try (TcpFakePrinter fake = TcpFakePrinter.answering((byte) 0x16, READY)) {
            UUID printer = probing(store, fake);
            UUID job = fixtures.testPage(store, printer);

            fixtures.runWorkerUntil(() -> waitingWith(job, "COVER_OPEN"), "RETRY_WAIT with COVER_OPEN");
            for (int attempt = 2; attempt <= 5; attempt++) {
                int expected = attempt;
                fixtures.dueNow(job);
                fixtures.runWorkerUntil(() -> {
                    Map<String, Object> row = fixtures.job(job);
                    return (int) row.get("attempts") >= expected && !"PROCESSING".equals(row.get("status"));
                }, "attempt " + attempt);
            }

            assertThat(fixtures.job(job)).containsEntry("status", "FAILED").containsEntry("attempts", 5)
                    .containsEntry("last_error", "COVER_OPEN");
            assertThat(fake.received()).hasSizeGreaterThanOrEqualTo(5)
                    .allSatisfy(bytes -> assertThat(HexFormat.of().formatHex(bytes)).isEqualTo("100402"));
            assertThat(fixtures.printerRow(printer)).containsEntry("last_status", "COVER_OPEN")
                    .containsEntry("version", 0L);
        }
    }

    @Test
    void paperOutSendsNothingAndLeavesTheJobWaiting() throws Exception {
        UUID store = stores.newStore();
        try (TcpFakePrinter fake = TcpFakePrinter.answering(READY, (byte) 0x72)) {
            UUID job = fixtures.testPage(store, probing(store, fake));

            fixtures.runWorkerUntil(() -> waitingWith(job, "PAPER_OUT"), "RETRY_WAIT with PAPER_OUT");

            assertThat(fake.received()).allSatisfy(bytes -> assertThat(HexFormat.of().formatHex(bytes))
                    .isEqualTo("100402" + "100404"));
        }
    }

    @Test
    void paperNearEndPrintsAndShowsOnThePrinterList() throws Exception {
        UUID store = stores.newStore();
        try (TcpFakePrinter fake = TcpFakePrinter.answering(READY, (byte) 0x1E)) {
            UUID printer = probing(store, fake);
            UUID job = fixtures.testPage(store, printer);

            fixtures.runWorkerUntil(() -> "SENT".equals(fixtures.jobStatus(job)), "test page sent");

            assertThat(fake.awaitPayloads(java.nio.charset.StandardCharsets.ISO_8859_1,
                    text -> text.contains("TESTE DE IMPRESS"), 1, Duration.ofSeconds(5))).hasSize(1);
            mvc.perform(get(ProductionFixtures.PRINTERS).param("storeId", store.toString())
                            .with(inStore(store, Permission.PRODUCTION_MANAGE))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].lastStatus").value("PAPER_NEAR_END"))
                    .andExpect(jsonPath("$.content[0].lastStatusAt").isNotEmpty())
                    .andExpect(jsonPath("$.content[0].version").value(0));
        }
    }

    private UUID probing(UUID store, TcpFakePrinter fake) throws Exception {
        return fixtures.printer(store, "P" + UUID.randomUUID().toString().substring(0, 6), fake.host(), fake.port(),
                "\"statusProbe\": true");
    }

    /** One read of the row: the status and its cause together, never a state a worker has since changed. */
    private boolean waitingWith(UUID job, String lastError) {
        Map<String, Object> row = fixtures.job(job);
        return "RETRY_WAIT".equals(row.get("status")) && lastError.equals(row.get("last_error"));
    }
}
