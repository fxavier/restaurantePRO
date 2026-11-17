package com.xavier.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.config.security.TestJwtFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What a client and an operator see on the real server (not MockMvc): the API is stateless (D176: no session, no
 * cookie, a valid bearer token on every call), and actuator lives on its own port.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@Import({TestcontainersConfiguration.class, WebServerSecurityTest.ProbeController.class})
class WebServerSecurityTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @LocalManagementPort
    int managementPort;

    @Autowired
    JdbcClient jdbc;

    @Test
    void theApiIsStatelessAndSetsNoCookie() throws Exception {
        HttpResponse<String> anonymous = send(HttpRequest.newBuilder(api("/api/v1/auth/server-probe")).GET());
        HttpResponse<String> member = send(HttpRequest.newBuilder(api("/api/v1/auth/server-probe"))
                .header("Authorization", "Bearer " + TestJwtFactory.account("kc-" + java.util.UUID.randomUUID())
                        .encode()).POST(HttpRequest.BodyPublishers.noBody()));

        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(anonymous.headers().firstValue("WWW-Authenticate")).hasValue("Bearer");
        assertThat(member.statusCode()).as("a valid token without membership").isEqualTo(403);
        assertThat(anonymous.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(member.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(jdbc.sql("SELECT to_regclass('shared.spring_session') IS NULL").query(Boolean.class).single())
                .as("V1011 dropped Spring Session's tables").isTrue();
    }

    @Test
    void actuatorHealthIsOpenButOtherEndpointsNeedAnAdmin() throws Exception {
        HttpResponse<String> health = send(HttpRequest.newBuilder(management("/actuator/health")).GET());
        HttpResponse<String> metrics = send(HttpRequest.newBuilder(management("/actuator/metrics")).GET());

        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(metrics.statusCode()).isEqualTo(401);
        assertThat(metrics.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/problem+json"));
    }

    @Test
    void actuatorIsNotServedOnTheApplicationPort() throws Exception {
        HttpResponse<String> health = send(HttpRequest.newBuilder(api("/actuator/health")).GET());

        assertThat(health.statusCode()).isNotEqualTo(200);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI api(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private URI management(String path) {
        return URI.create("http://localhost:" + managementPort + path);
    }

    @RestController
    static class ProbeController {

        @GetMapping("/api/v1/auth/server-probe")
        String open() {
            return "open";
        }

        @PostMapping("/api/v1/auth/server-probe")
        String write() {
            return "written";
        }
    }
}
