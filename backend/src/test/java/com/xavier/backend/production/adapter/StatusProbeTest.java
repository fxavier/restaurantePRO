package com.xavier.backend.production.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.production.TcpFakePrinter;
import com.xavier.backend.production.model.PrinterAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * D157, A65: with the probe on, {@code DLE EOT 2} and {@code DLE EOT 4} go out before the payload. Cover open, paper
 * end or an error sends nothing (safe to retry, like a refused connection); paper near end sends and says so; a
 * printer that never answers still prints. With the probe off nothing but the payload is written.
 */
class StatusProbeTest {

    static final byte READY = 0x12;
    static final byte COVER_OPEN = 0x16;
    static final byte ERROR = 0x52;
    static final byte PAPER_END = 0x72;
    static final byte PAPER_NEAR_END = 0x1E;
    static final String PROBE_OFFLINE = "100402";
    static final String PROBE_PAPER = "100404";
    static final byte[] PAYLOAD = "ticket".getBytes(StandardCharsets.US_ASCII);

    final EscPosTcpTransport transport = new EscPosTcpTransport(Duration.ofSeconds(2), Duration.ofSeconds(5));

    @AfterEach
    void stop() {
        transport.destroy();
    }

    @Test
    void aCoverOpenSendsNothing() throws Exception {
        try (TcpFakePrinter printer = TcpFakePrinter.answering(COVER_OPEN, READY)) {
            SendResult result = transport.send(address(printer), PAYLOAD, true);

            assertThat(result).isEqualTo(new SendResult(SendOutcome.NOT_READY_COVER_OPEN, false));
            assertThat(result.outcome().nothingWritten()).isTrue();
            assertThat(received(printer)).isEqualTo(PROBE_OFFLINE);
        }
    }

    @Test
    void aPrinterErrorSendsNothing() throws Exception {
        try (TcpFakePrinter printer = TcpFakePrinter.answering(ERROR, READY)) {
            assertThat(transport.send(address(printer), PAYLOAD, true).outcome())
                    .isEqualTo(SendOutcome.NOT_READY_ERROR);
            assertThat(received(printer)).isEqualTo(PROBE_OFFLINE);
        }
    }

    @Test
    void paperEndSendsNothing() throws Exception {
        try (TcpFakePrinter printer = TcpFakePrinter.answering(READY, PAPER_END)) {
            SendResult result = transport.send(address(printer), PAYLOAD, true);

            assertThat(result.outcome()).isEqualTo(SendOutcome.NOT_READY_PAPER_OUT);
            assertThat(received(printer)).isEqualTo(PROBE_OFFLINE + PROBE_PAPER);
        }
    }

    /** DLE EOT 2 bit 5: printing stopped at the paper end; the paper sensor is not even asked. */
    @Test
    void aPaperEndStopInTheOfflineStatusSendsNothing() throws Exception {
        try (TcpFakePrinter printer = TcpFakePrinter.answering((byte) 0x32, READY)) {
            assertThat(transport.send(address(printer), PAYLOAD, true).outcome())
                    .isEqualTo(SendOutcome.NOT_READY_PAPER_OUT);
            assertThat(received(printer)).isEqualTo(PROBE_OFFLINE);
        }
    }

    @Test
    void paperNearEndSendsAndReportsIt() throws Exception {
        try (TcpFakePrinter printer = TcpFakePrinter.answering(READY, PAPER_NEAR_END)) {
            SendResult result = transport.send(address(printer), PAYLOAD, true);

            assertThat(result).isEqualTo(new SendResult(SendOutcome.ACCEPTED, true));
            assertThat(received(printer)).isEqualTo(PROBE_OFFLINE + PROBE_PAPER + hex(PAYLOAD));
        }
    }

    @Test
    void aReadyPrinterGetsTheProbesThenThePayload() throws Exception {
        try (TcpFakePrinter printer = TcpFakePrinter.answering(READY, READY)) {
            assertThat(transport.send(address(printer), PAYLOAD, true))
                    .isEqualTo(new SendResult(SendOutcome.ACCEPTED, false));
            assertThat(received(printer)).isEqualTo(PROBE_OFFLINE + PROBE_PAPER + hex(PAYLOAD));
        }
    }

    /**
     * A printer without DLE EOT must still print: no answer within 500 ms sends. The probe's connection is closed and
     * the ticket goes on a fresh one, so a late answer can never sit unread under the ticket (D157, option (a)).
     */
    @Test
    void aSilentPrinterGetsThePayloadOnAFreshConnection() throws Exception {
        try (TcpFakePrinter printer = TcpFakePrinter.silent()) {
            long started = System.nanoTime();

            SendResult result = transport.send(address(printer), PAYLOAD, true);

            assertThat(result).isEqualTo(new SendResult(SendOutcome.ACCEPTED, false));
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
            assertThat(connections(printer, 2)).containsExactlyInAnyOrder(PROBE_OFFLINE, hex(PAYLOAD));
        }
    }

    /** An answer after the timeout is no answer: nothing of the ticket goes on the probe's connection. */
    @Test
    void aLateAnswerSendsThePayloadInFullOnASecondConnection() throws Exception {
        try (TcpFakePrinter printer = TcpFakePrinter.answeringLate(COVER_OPEN, READY, Duration.ofMillis(1500))) {
            SendResult result = transport.send(address(printer), PAYLOAD, true);

            assertThat(result).isEqualTo(new SendResult(SendOutcome.ACCEPTED, false));
            assertThat(connections(printer, 2)).containsExactlyInAnyOrder(PROBE_OFFLINE, hex(PAYLOAD));
        }
    }

    /** End of stream during the probe: the printer hung up; only status requests went out, so retry safely. */
    @Test
    void aPrinterThatHangsUpDuringTheProbeIsNotConnected() throws Exception {
        try (TcpFakePrinter printer = TcpFakePrinter.hangingUp()) {
            SendResult result = transport.send(address(printer), PAYLOAD, true);

            assertThat(result).isEqualTo(SendResult.of(SendOutcome.NOT_CONNECTED));
            assertThat(received(printer)).isEqualTo(PROBE_OFFLINE);
        }
    }

    /** An answer that is not a status byte ((b & 0x93) != 0x12) is no answer: the payload goes out. */
    @Test
    void anAnswerThatIsNotAStatusByteIsIgnored() throws Exception {
        try (TcpFakePrinter printer = TcpFakePrinter.answering((byte) 0x04, (byte) 0x60)) {
            assertThat(transport.send(address(printer), PAYLOAD, true).outcome()).isEqualTo(SendOutcome.ACCEPTED);
            assertThat(received(printer)).endsWith(hex(PAYLOAD));
        }
    }

    @Test
    void withTheProbeOffOnlyThePayloadIsWritten() throws Exception {
        try (TcpFakePrinter printer = TcpFakePrinter.answering(COVER_OPEN, PAPER_END)) {
            assertThat(transport.send(address(printer), PAYLOAD, false))
                    .isEqualTo(new SendResult(SendOutcome.ACCEPTED, false));
            assertThat(received(printer)).isEqualTo(hex(PAYLOAD));
        }
    }

    private static PrinterAddress address(TcpFakePrinter printer) {
        return new PrinterAddress(printer.host(), printer.port());
    }

    /** Everything the one connection carried, probes included, once it closed. */
    private static String received(TcpFakePrinter printer) throws InterruptedException {
        List<byte[]> connections = printer.awaitConnections(1, Duration.ofSeconds(5));
        assertThat(connections).hasSize(1);
        return hex(connections.getFirst());
    }

    /** The raw bytes of {@code count} closed connections, in no order. */
    private static List<String> connections(TcpFakePrinter printer, int count) throws InterruptedException {
        return printer.awaitConnections(count, Duration.ofSeconds(5)).stream().map(StatusProbeTest::hex).toList();
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
