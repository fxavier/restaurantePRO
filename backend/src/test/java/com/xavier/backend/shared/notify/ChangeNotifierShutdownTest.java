package com.xavier.backend.shared.notify;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * D89 (3): an open change stream must not hold the shutdown. The web server's graceful shutdown waits for active
 * requests (up to {@code spring.lifecycle.timeout-per-shutdown-phase}, 30 s), and an SSE stream stays active until it
 * is completed; {@link ChangeNotifier} completes every stream before that phase. A real Tomcat on a random port with
 * the notifier and a bare stream endpoint; the backend's default shutdown settings apply.
 */
class ChangeNotifierShutdownTest {

    @Test
    void aContextCloseWithAnOpenStreamTakesLessThanTwoSeconds() throws Exception {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(StreamApp.class)
                .web(WebApplicationType.SERVLET)
                .properties("server.port=0", "spring.main.banner-mode=off")
                .run();
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<InputStream> stream = client.sendAsync(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/stream")).build(),
                        HttpResponse.BodyHandlers.ofInputStream())
                .get(10, TimeUnit.SECONDS);
        assertThat(stream.statusCode()).isEqualTo(200);

        long started = System.nanoTime();
        context.close();
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(took).isLessThan(Duration.ofSeconds(2));
        closeQuietly(stream.body());
        client.close();
    }

    private static void closeQuietly(InputStream body) {
        try {
            body.close();
        } catch (IOException ignored) {
            // the server already ended the stream
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({TomcatServletWebServerAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class})
    @Import({ChangeNotifier.class, StreamController.class})
    static class StreamApp {
    }

    @RestController
    static class StreamController {

        private final ChangeNotifier notifier;

        StreamController(ChangeNotifier notifier) {
            this.notifier = notifier;
        }

        /** Sends a comment at once, so the response is committed and the client sees the stream open. */
        @GetMapping("/stream")
        SseEmitter stream() throws IOException {
            SseEmitter emitter = notifier.subscribe(UUID.randomUUID());
            emitter.send(SseEmitter.event().comment("open"));
            return emitter;
        }
    }
}
