package com.xavier.backend.production.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.production.TcpFakePrinter;
import com.xavier.backend.production.model.PrinterAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * D57 against a real socket: ACCEPTED when the bytes were written and the connection closed, NOT_CONNECTED when
 * nothing listens (safe to retry), INTERRUPTED_AFTER_WRITE when the write did not finish in time (the printer may
 * have printed part of it: UNKNOWN). D48: never inside a transaction.
 */
class EscPosTcpTransportTest {

    final EscPosTcpTransport transport = new EscPosTcpTransport(Duration.ofSeconds(2), Duration.ofMillis(500));

    @AfterEach
    void stop() {
        transport.destroy();
    }

    @Test
    void timeoutsThatOutliveTheJobLeaseAreRefusedAtStartup() {
        assertThatThrownBy(() -> new EscPosTcpTransport(Duration.ofSeconds(2), Duration.ofSeconds(58)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("lease");
    }

    /** D157: connect + the probe's two 500 ms reads + write must stay below the lease too. */
    @Test
    void theStatusProbeCountsAgainstTheJobLease() {
        assertThatThrownBy(() -> new EscPosTcpTransport(Duration.ofSeconds(2), Duration.ofSeconds(57)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("lease");
    }

    /** D157 option (a): a silent probe reconnects, so the budget is connect + probe + connect + write. */
    @Test
    void theSecondConnectionAfterASilentProbeCountsAgainstTheJobLease() {
        assertThatThrownBy(() -> new EscPosTcpTransport(Duration.ofSeconds(2), Duration.ofSeconds(55)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("lease");
    }

    @Test
    void aListeningPrinterReceivesTheBytes() throws Exception {
        try (TcpFakePrinter printer = TcpFakePrinter.accepting()) {
            SendOutcome outcome = transport.send(new PrinterAddress(printer.host(), printer.port()),
                    "hello kitchen".getBytes(StandardCharsets.US_ASCII), false).outcome();

            assertThat(outcome).isEqualTo(SendOutcome.ACCEPTED);
            assertThat(printer.awaitPayloads(StandardCharsets.US_ASCII, "hello kitchen"::equals, 1,
                    Duration.ofSeconds(5))).hasSize(1);
        }
    }

    @Test
    void aPrinterThatRefusesTheConnectionIsNotConnected() throws Exception {
        SendOutcome outcome = transport.send(new PrinterAddress("127.0.0.1", TcpFakePrinter.closedPort()),
                new byte[] {1, 2, 3}, true).outcome();

        assertThat(outcome).isEqualTo(SendOutcome.NOT_CONNECTED);
    }

    @Test
    void aPrinterThatStopsReadingInterruptsTheWriteAfterTheTimeout() throws Exception {
        try (TcpFakePrinter printer = TcpFakePrinter.hanging()) {
            long started = System.nanoTime();

            SendOutcome outcome = transport.send(new PrinterAddress(printer.host(), printer.port()),
                    new byte[16 * 1024 * 1024], false).outcome();

            assertThat(outcome).isEqualTo(SendOutcome.INTERRUPTED_AFTER_WRITE);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
        }
    }

    @Test
    void sendingInsideATransactionIsRefusedBeforeConnecting() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> transport.send(new PrinterAddress("127.0.0.1", 9), new byte[1], false))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("transaction");
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }
}
