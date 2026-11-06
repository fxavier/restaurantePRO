package com.xavier.backend.production.adapter;

import com.xavier.backend.production.model.PrintJob;
import com.xavier.backend.production.model.PrinterAddress;
import com.xavier.backend.shared.ExternalCalls;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Raw ESC/POS over TCP (port 9100) with {@code java.net.Socket} (D57): a 2 s connect timeout and a 5 s write timeout.
 * A socket has no write timeout of its own, so a watchdog closes it when the write takes longer, which ends the
 * blocked write with an exception.
 */
@Component
public class EscPosTcpTransport implements PrinterTransport, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(EscPosTcpTransport.class);

    private final Duration connectTimeout;
    private final Duration writeTimeout;
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("printer-write-watchdog").daemon().factory());

    public EscPosTcpTransport(@Value("${restaurantpro.production.printer.connect-timeout:PT2S}") Duration connectTimeout,
                              @Value("${restaurantpro.production.printer.write-timeout:PT5S}") Duration writeTimeout) {
        // a send outliving its job's lease would let the printer's next job go out while this one is still writing
        if (connectTimeout.plus(writeTimeout).compareTo(PrintJob.LEASE) >= 0) {
            throw new IllegalStateException("printer connect-timeout + write-timeout must stay below the "
                    + PrintJob.LEASE + " print-job lease");
        }
        this.connectTimeout = connectTimeout;
        this.writeTimeout = writeTimeout;
    }

    @Override
    public SendOutcome send(PrinterAddress address, byte[] payload) {
        ExternalCalls.requireNoTransaction();
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(address.host(), address.port()),
                    Math.toIntExact(connectTimeout.toMillis()));
        } catch (IOException | IllegalArgumentException e) {
            closeQuietly(socket);
            log.warn("Printer {}:{} not reachable: {}", address.host(), address.port(), e.toString());
            return SendOutcome.NOT_CONNECTED;
        }
        ScheduledFuture<?> timeout = watchdog.schedule(() -> closeQuietly(socket), writeTimeout.toMillis(),
                TimeUnit.MILLISECONDS);
        try (socket; OutputStream out = socket.getOutputStream()) {
            out.write(payload);
            out.flush();
            return SendOutcome.ACCEPTED;
        } catch (IOException e) {
            log.warn("Printer {}:{} send interrupted after connecting: {}", address.host(), address.port(),
                    e.toString());
            return SendOutcome.INTERRUPTED_AFTER_WRITE;
        } finally {
            timeout.cancel(false);
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // closing is best effort
        }
    }

    @Override
    public void destroy() {
        watchdog.shutdownNow();
    }
}
