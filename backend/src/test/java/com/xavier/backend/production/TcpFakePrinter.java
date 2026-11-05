package com.xavier.backend.production;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * A real TCP printer on 127.0.0.1 (D57): a {@link ServerSocket} that either reads every connection to its end
 * ({@link Mode#ACCEPT}) or accepts and never reads ({@link Mode#HANG}, with a tiny receive buffer so a large write
 * blocks). {@link #down()} closes the listening socket, so connections are refused; {@link #up()} listens again on the
 * same port. Payloads from other tests' leftover jobs may arrive too: match on content.
 */
public final class TcpFakePrinter implements AutoCloseable {

    public enum Mode { ACCEPT, HANG }

    private final List<byte[]> received = new CopyOnWriteArrayList<>();
    private final List<Socket> hanging = new CopyOnWriteArrayList<>();
    private final int port;
    private volatile Mode mode;
    private volatile ServerSocket server;

    private TcpFakePrinter(Mode mode, int port) throws IOException {
        this.mode = mode;
        listen(port);
        this.port = server.getLocalPort();
    }

    public static TcpFakePrinter accepting() throws IOException {
        return new TcpFakePrinter(Mode.ACCEPT, 0);
    }

    public static TcpFakePrinter hanging() throws IOException {
        return new TcpFakePrinter(Mode.HANG, 0);
    }

    /** A port nothing listens on (bound, then released). */
    public static int closedPort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return probe.getLocalPort();
        }
    }

    public int port() {
        return port;
    }

    public String host() {
        return "127.0.0.1";
    }

    public void down() throws IOException {
        server.close();
    }

    public void up() throws IOException {
        listen(port);
    }

    public List<byte[]> received() {
        return List.copyOf(received);
    }

    /** The payloads whose text (in {@code charset}) matches, waiting up to {@code timeout} for {@code count}. */
    public List<String> awaitPayloads(Charset charset, Predicate<String> match, int count, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        List<String> mine = matching(charset, match);
        while (mine.size() < count && System.nanoTime() < deadline) {
            Thread.sleep(50);
            mine = matching(charset, match);
        }
        return mine;
    }

    public List<String> matching(Charset charset, Predicate<String> match) {
        return received.stream().map(bytes -> new String(bytes, charset)).filter(match).toList();
    }

    private void listen(int onPort) throws IOException {
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.setReceiveBufferSize(1024);
        socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), onPort), 50);
        server = socket;
        Thread.ofVirtual().start(() -> acceptLoop(socket));
    }

    private void acceptLoop(ServerSocket socket) {
        while (!socket.isClosed()) {
            try {
                Socket connection = socket.accept();
                if (mode == Mode.HANG) {
                    hanging.add(connection);
                } else {
                    Thread.ofVirtual().start(() -> readAll(connection));
                }
            } catch (IOException closed) {
                return;
            }
        }
    }

    private void readAll(Socket connection) {
        try (connection; InputStream in = connection.getInputStream()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            in.transferTo(bytes);
            received.add(bytes.toByteArray());
        } catch (IOException e) {
            // a broken connection is the sender's outcome to report
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
        for (Socket socket : hanging) {
            socket.close();
        }
    }
}
