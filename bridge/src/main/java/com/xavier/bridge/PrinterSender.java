package com.xavier.bridge;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Raw ESC/POS over TCP (port 9100) on the store's LAN (D57, D187: the bridge sends what the backend sent before): a 2 s connect timeout and a 5 s write timeout.
 * A socket has no write timeout of its own, so a watchdog closes it when the write takes longer, which ends the
 * blocked write with an exception.
 * <p>
 * D157: with the probe on, {@code DLE EOT 2} (offline cause) and {@code DLE EOT 4} (paper sensor) go out on the open
 * connection first, each answered by one status byte within 500 ms. Cover open, paper end or an error sends nothing;
 * end of stream (the printer hung up) is NOT_CONNECTED. No answer in time (a printer without real-time status) still
 * prints, on a fresh connection (option (a)): a late answer must not sit unread on the connection that carries the
 * ticket, where closing it could reset the connection before the printer has read everything. Only status requests
 * were written to the first one. There is no acknowledgement after the send: the printer answers status before it
 * prints its buffer.
 */
final class PrinterSender implements AutoCloseable {

    private static final System.Logger log = System.getLogger(PrinterSender.class.getName());
    /** The backend's print-job lease (D50): a send must end before the job is presumed lost (UNKNOWN). */
    static final Duration JOB_LEASE = Duration.ofSeconds(60);

    static final Duration PROBE_READ_TIMEOUT = Duration.ofMillis(500);
    /** Both status reads. */
    static final Duration PROBE_BUDGET = PROBE_READ_TIMEOUT.multipliedBy(2);

    private static final byte DLE = 0x10;
    private static final byte EOT = 0x04;
    private static final int OFFLINE_STATUS = 2;
    private static final int PAPER_STATUS = 4;
    /** {@link #status}: no answer within the read timeout. */
    private static final int SILENT = -1;
    /** {@link #status}: an answer that is not a status byte. */
    private static final int NOT_A_STATUS = -2;

    private final Duration connectTimeout;
    private final Duration writeTimeout;
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("printer-write-watchdog").daemon().factory());

    PrinterSender(Duration connectTimeout, Duration writeTimeout) {
        // a send outliving its job's lease would let the printer's next job go out while this one is still writing
        // a silent probe reconnects: connect + probe + connect + write
        if (connectTimeout.multipliedBy(2).plus(PROBE_BUDGET).plus(writeTimeout).compareTo(JOB_LEASE) >= 0) {
            throw new IllegalStateException("printer 2 x connect-timeout + status probe (" + PROBE_BUDGET
                    + ") + write-timeout must stay below the " + JOB_LEASE + " print-job lease");
        }
        this.connectTimeout = connectTimeout;
        this.writeTimeout = writeTimeout;
    }

    /** One job to {@code host:port}; never throws for a printer problem, it reports it. */
    SendResult send(String host, int port, byte[] payload, boolean probeStatus) {
        Address address = new Address(host, port);
        Socket socket = connect(address);
        if (socket == null) {
            return SendResult.of(SendOutcome.NOT_CONNECTED);
        }
        boolean paperNearEnd = false;
        if (probeStatus) {
            Probe probe = probe(socket, address);
            if (probe.silent()) {
                closeQuietly(socket);
                socket = connect(address);
                if (socket == null) {
                    return SendResult.of(SendOutcome.NOT_CONNECTED);
                }
            } else if (probe.outcome() != SendOutcome.ACCEPTED) {
                closeQuietly(socket);
                return SendResult.of(probe.outcome());
            }
            paperNearEnd = probe.paperNearEnd();
        }
        return write(socket, address, payload, paperNearEnd);
    }

    /** The ticket's bytes on {@code socket}, which this closes; the watchdog ends a write that takes too long. */
    private SendResult write(Socket socket, Address address, byte[] payload, boolean paperNearEnd) {
        ScheduledFuture<?> timeout = watchdog.schedule(() -> closeQuietly(socket), writeTimeout.toMillis(),
                TimeUnit.MILLISECONDS);
        try (socket; OutputStream out = socket.getOutputStream()) {
            out.write(payload);
            out.flush();
            return new SendResult(SendOutcome.ACCEPTED, paperNearEnd);
        } catch (IOException e) {
            log.log(System.Logger.Level.WARNING, "Printer {0}:{1,number,#} send interrupted after connecting: {2}",
                    address.host(), address.port(), e.toString());
            return SendResult.of(SendOutcome.INTERRUPTED_AFTER_WRITE);
        } finally {
            timeout.cancel(false);
        }
    }

    /** {@code null} when the printer does not accept the connection in time. */
    private Socket connect(Address address) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(address.host(), address.port()),
                    Math.toIntExact(connectTimeout.toMillis()));
            return socket;
        } catch (IOException | IllegalArgumentException e) {
            closeQuietly(socket);
            log.log(System.Logger.Level.WARNING, "Printer {0}:{1,number,#} not reachable: {2}", address.host(),
                    address.port(), e.toString());
            return null;
        }
    }

    /** ACCEPTED here means "go ahead and send"; {@code silent}: on a fresh connection. */
    private record Probe(SendOutcome outcome, boolean paperNearEnd, boolean silent) {

        static final Probe SILENT_PRINTER = new Probe(SendOutcome.ACCEPTED, false, true);

        static Probe go(boolean paperNearEnd) {
            return new Probe(SendOutcome.ACCEPTED, paperNearEnd, false);
        }
    }

    private static Probe probe(Socket socket, Address address) {
        try {
            socket.setSoTimeout(Math.toIntExact(PROBE_READ_TIMEOUT.toMillis()));
            int offline = status(socket, OFFLINE_STATUS);
            if (offline == SILENT) {
                return Probe.SILENT_PRINTER;
            }
            if (offline == NOT_A_STATUS) {
                return Probe.go(false);
            }
            if ((offline & 0x04) != 0) {
                return notReady(address, SendOutcome.NOT_READY_COVER_OPEN);
            }
            if ((offline & 0x20) != 0) {
                return notReady(address, SendOutcome.NOT_READY_PAPER_OUT);
            }
            if ((offline & 0x40) != 0) {
                return notReady(address, SendOutcome.NOT_READY_ERROR);
            }
            int paper = status(socket, PAPER_STATUS);
            if (paper == SILENT) {
                return Probe.SILENT_PRINTER;
            }
            if (paper != NOT_A_STATUS && (paper & 0x60) != 0) {
                return notReady(address, SendOutcome.NOT_READY_PAPER_OUT);
            }
            return Probe.go(paper != NOT_A_STATUS && (paper & 0x0C) != 0);
        } catch (IOException e) {
            // end of stream or a broken connection: only status requests were written, and they print nothing
            log.log(System.Logger.Level.WARNING, "Printer {0}:{1,number,#} connection lost during the status probe: {2}",
                    address.host(), address.port(), e.toString());
            return new Probe(SendOutcome.NOT_CONNECTED, false, false);
        }
    }

    /**
     * The status byte for {@code DLE EOT n}, {@link #SILENT} when none came in time, {@link #NOT_A_STATUS} when the
     * byte is not a status byte; end of stream is an {@link EOFException}.
     */
    private static int status(Socket socket, int n) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write(new byte[] {DLE, EOT, (byte) n});
        out.flush();
        InputStream in = socket.getInputStream();
        try {
            int answer = in.read();
            if (answer == -1) {
                throw new EOFException("the printer closed the connection during the status probe");
            }
            return (answer & 0x93) == 0x12 ? answer : NOT_A_STATUS;
        } catch (SocketTimeoutException silent) {
            return SILENT;
        }
    }

    private static Probe notReady(Address address, SendOutcome outcome) {
        log.log(System.Logger.Level.WARNING, "Printer {0}:{1,number,#} is not ready ({2}): nothing sent",
                address.host(), address.port(), outcome);
        return new Probe(outcome, false, false);
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // closing is best effort
        }
    }

    /** Where a network printer listens. */
    private record Address(String host, int port) {
    }

    @Override
    public void close() {
        watchdog.shutdownNow();
    }
}
