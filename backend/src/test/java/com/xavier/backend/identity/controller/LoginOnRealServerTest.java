package com.xavier.backend.identity.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.identity.IdentityTest;
import com.xavier.backend.identity.service.AdminBootstrapProperties;
import com.xavier.backend.identity.service.AdminBootstrapService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * Acceptance criterion 2 (decisions §7) as a browser sees it, on the real embedded server: a password login sets
 * a Secure, HttpOnly, SameSite session cookie, and mutating requests without the CSRF token are refused.
 * This is how an end-to-end client logs in: GET anything to receive XSRF-TOKEN, then POST with the cookie echoed
 * in X-XSRF-TOKEN.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "restaurantpro.bootstrap.company.name=Test Company",
        "restaurantpro.bootstrap.company.tax-id=TEST-TAX-ID",
        "restaurantpro.bootstrap.company.country=MZ",
        "restaurantpro.bootstrap.company.currency=MZN"
})
class LoginOnRealServerTest {

    static final String PASSWORD = "real server passphrase";

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @Autowired
    AdminBootstrapService bootstrap;

    @TempDir
    Path folder;

    @Test
    void aPasswordLoginSetsASecureHttpOnlySameSiteSessionCookieAndCsrfGuardsEveryPost() throws Exception {
        String username = IdentityFixtures.unique("admin");
        bootstrap.createAdmin(new AdminBootstrapProperties(username,
                Files.writeString(folder.resolve("password"), PASSWORD)));
        String body = IdentityFixtures.json("username", username, "password", PASSWORD);

        HttpResponse<String> anonymous = send(HttpRequest.newBuilder(api("/api/v1/auth/session")).GET());
        assertThat(anonymous.statusCode()).isEqualTo(401);
        String csrf = cookieValue(anonymous, "XSRF-TOKEN").orElseThrow();

        HttpResponse<String> withoutCsrf = send(json(api("/api/v1/auth/login"), body)
                .header("Cookie", "XSRF-TOKEN=" + csrf));
        assertThat(withoutCsrf.statusCode()).isEqualTo(403);

        HttpResponse<String> login = send(json(api("/api/v1/auth/login"), body)
                .header("Cookie", "XSRF-TOKEN=" + csrf).header("X-XSRF-TOKEN", csrf));
        assertThat(login.statusCode()).isEqualTo(200);
        String sessionCookie = setCookie(login, "SESSION").orElseThrow();
        assertThat(sessionCookie).contains("; Secure").contains("; HttpOnly").contains("; SameSite=Lax");
        String session = sessionCookie.substring("SESSION=".length(), sessionCookie.indexOf(';'));
        // the CSRF token is rotated at login, as Spring Security's own login filters do
        String freshCsrf = cookieValue(login, "XSRF-TOKEN").orElse("");
        assertThat(freshCsrf).isNotEmpty().isNotEqualTo(csrf);

        HttpResponse<String> current = send(HttpRequest.newBuilder(api("/api/v1/auth/session"))
                .header("Cookie", "SESSION=" + session).GET());
        assertThat(current.statusCode()).isEqualTo(200);
        assertThat(current.body()).contains(username);

        HttpResponse<String> logoutWithoutCsrf = send(HttpRequest.newBuilder(api("/api/v1/auth/logout"))
                .header("Cookie", "SESSION=" + session).POST(HttpRequest.BodyPublishers.noBody()));
        assertThat(logoutWithoutCsrf.statusCode()).isEqualTo(403);

        HttpResponse<String> logout = send(HttpRequest.newBuilder(api("/api/v1/auth/logout"))
                .header("Cookie", "SESSION=" + session + "; XSRF-TOKEN=" + freshCsrf)
                .header("X-XSRF-TOKEN", freshCsrf).POST(HttpRequest.BodyPublishers.noBody()));
        assertThat(logout.statusCode()).isEqualTo(204);
    }

    private HttpRequest.Builder json(URI uri, String body) {
        return HttpRequest.newBuilder(uri).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI api(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static Optional<String> setCookie(HttpResponse<?> response, String name) {
        List<String> cookies = response.headers().allValues("Set-Cookie");
        return cookies.stream().filter(c -> c.startsWith(name + "=")).reduce((first, last) -> last);
    }

    private static Optional<String> cookieValue(HttpResponse<?> response, String name) {
        return setCookie(response, name).map(cookie -> cookie.substring(name.length() + 1, cookie.indexOf(';')));
    }
}
