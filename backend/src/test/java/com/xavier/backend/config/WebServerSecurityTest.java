package com.xavier.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.TestcontainersConfiguration;
import jakarta.servlet.http.HttpSession;
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
 * What a browser and an operator see on the real server (not MockMvc): the session cookie attributes
 * are applied by Boot's embedded-server session configuration, and actuator lives on its own port.
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
    void sessionsLiveInPostgresBehindASecureHttpOnlySameSiteCookie() throws Exception {
        HttpResponse<String> first = send(HttpRequest.newBuilder(api("/api/v1/auth/server-probe")).GET());
        String csrfToken = cookieValue(first, "XSRF-TOKEN");

        HttpResponse<String> login = send(HttpRequest.newBuilder(api("/api/v1/auth/server-probe/session"))
                .header("Cookie", "XSRF-TOKEN=" + csrfToken)
                .header("X-XSRF-TOKEN", csrfToken)
                .POST(HttpRequest.BodyPublishers.noBody()));

        assertThat(login.statusCode()).isEqualTo(200);
        String sessionCookie = setCookie(login, "SESSION");
        assertThat(sessionCookie).contains("; Secure").contains("; HttpOnly").contains("; SameSite=Lax");
        assertThat(jdbc.sql("SELECT count(*) FROM shared.spring_session WHERE session_id = :id")
                .param("id", login.body()).query(Long.class).single()).isEqualTo(1);
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

    private static String setCookie(HttpResponse<?> response, String name) {
        List<String> cookies = response.headers().allValues("Set-Cookie");
        return cookies.stream().filter(c -> c.startsWith(name + "=")).findFirst()
                .orElseThrow(() -> new AssertionError("no " + name + " cookie in " + cookies));
    }

    private static String cookieValue(HttpResponse<?> response, String name) {
        String cookie = setCookie(response, name);
        return cookie.substring(name.length() + 1, cookie.indexOf(';'));
    }

    @RestController
    static class ProbeController {

        @GetMapping("/api/v1/auth/server-probe")
        String open() {
            return "open";
        }

        @PostMapping("/api/v1/auth/server-probe/session")
        String startSession(HttpSession session) {
            session.setAttribute("probe", "value");
            return session.getId();
        }
    }
}
