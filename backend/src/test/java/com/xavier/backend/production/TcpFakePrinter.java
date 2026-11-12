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
 * <p>
 * D157: an {@link #answering} printer replies to the real-time status requests {@code DLE EOT 2} and {@code DLE EOT 4}
 * while it reads, with one status byte each; a {@link #silent()} one (as {@link #accepting()}) never replies.
 * {@link #received()} keeps each connection's raw bytes, the status requests included.
 */
public final class TcpFakePrinter implements AutoCloseable {

    public enum Mode { ACCEPT, HANG }

    private final List<byte[]> received = new CopyOnWriteArrayList<>();
    private final List<Socket> hanging = new CopyOnWriteArrayList<>();
    private final int port;
    private volatile Mode mode;
    private volatile ServerSocket server;
    /** The replies to DLE EOT 2 and DLE EOT 4; {@code null}: never replies. */
    private final byte[] status;

    private TcpFakePrinter(Mode mode, int port) throws IOException {
        this(mode, port, null);
    }

    private TcpFakePrinter(Mode mode, int port, byte[] status) throws IOException {
        this.mode = mode;
        this.status = status;
        listen(port);
        this.port = server.getLocalPort();
    }

    public static TcpFakePrinter accepting() throws IOException {
        return new TcpFakePrinter(Mode.ACCEPT, 0);
    }

    /** Replies {@code offlineStatus} to {@code 10 04 02} and {@code paperStatus} to {@code 10 04 04}. */
    public static TcpFakePrinter answering(byte offlineStatus, byte paperStatus) throws IOException {
        return new TcpFakePrinter(Mode.ACCEPT, 0, new byte[] {offlineStatus, paperStatus});
    }

    /** Reads everything and never answers a status request (a printer without DLE EOT). */
    public static TcpFakePrinter silent() throws IOException {
        return accepting();
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

    /** Each closed connection's raw bytes, waiting up to {@code timeout} for {@code count} of them. */
    public List<byte[]> awaitConnections(int count, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (received.size() < count && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        return received();
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
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (connection; InputStream in = connection.getInputStream()) {
            if (status == null) {
                in.transferTo(bytes);
            } else {
                answerWhileReading(connection, in, bytes);
            }
        } catch (IOException e) {
            // a broken connection is the sender's outcome to report
        } finally {
            received.add(bytes.toByteArray());
        }
    }

    /** Byte by byte, so a status request is answered as soon as its third byte arrives. */
    private void answerWhileReading(Socket connection, InputStream in, ByteArrayOutputStream bytes)
            throws IOException {
        int beforeLast = -1;
        int last = -1;
        for (int b = in.read(); b != -1; b = in.read()) {
            bytes.write(b);
            if (beforeLast == 0x10 && last == 0x04 && (b == 0x02 || b == 0x04)) {
                connection.getOutputStream().write(status[b == 0x02 ? 0 : 1]);
                connection.getOutputStream().flush();
            }
            beforeLast = last;
            last = b;
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
