package com.xavier.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.access.Actor;
import jakarta.servlet.http.Cookie;
import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.web.servlet.MockMvc;

/**
 * D38: changing {@link Actor}'s shape makes the sessions stored before an upgrade unreadable. Such a session must
 * behave as "not logged in" (401, the client logs in again), never as a 500 on every request.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OutdatedSessionTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcIndexedSessionRepository sessions;

    @Autowired
    JdbcClient jdbc;

    @Test
    void aSessionStoredByAnOlderActorShapeIsTreatedAsLoggedOut() throws Exception {
        Actor actor = Actor.account(UUID.randomUUID(), Set.of());
        SecurityContext context = new SecurityContextImpl(
                UsernamePasswordAuthenticationToken.authenticated(actor, null, java.util.List.of()));
        @SuppressWarnings("unchecked")
        SessionRepository<Session> repository = (SessionRepository<Session>) (SessionRepository<?>) sessions;
        Session session = repository.createSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, actor.userId().toString());
        repository.save(session);
        // what R1 wrote: an Actor without kind
        byte[] stored = withoutKindField(serialize(context));
        assertThat(jdbc.sql("""
                        UPDATE shared.spring_session_attributes SET attribute_bytes = :bytes
                        WHERE attribute_name = :name AND session_primary_id =
                              (SELECT primary_id FROM shared.spring_session WHERE session_id = :id)""")
                .param("bytes", stored).param("name", HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)
                .param("id", session.getId()).update()).isOne();
        Cookie cookie = new Cookie("SESSION",
                Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));

        mvc.perform(get("/api/v1/backoffice/anything").cookie(cookie))
                .andExpect(status().isUnauthorized());
    }

    private static byte[] serialize(Object value) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        return bytes.toByteArray();
    }

    /**
     * The R1 {@code Actor} had no {@code kind}. Renaming that field in the stream's class descriptor gives exactly
     * such a stream: Java ignores the unknown field and hands the record's canonical constructor {@code kind = null}.
     * (A record's serialVersionUID is not checked, so bumping it alone would not reject anything.)
     */
    private static byte[] withoutKindField(byte[] stream) {
        byte[] field = {'L', 0, 4, 'k', 'i', 'n', 'd'};
        for (int i = 0; i + field.length <= stream.length; i++) {
            if (matches(stream, i, field)) {
                stream[i + field.length - 1] = 'x'; // "kind" -> "kinx"
                return stream;
            }
        }
        throw new IllegalStateException("Actor.kind not found in the serialized session");
    }

    private static boolean matches(byte[] stream, int offset, byte[] name) {
        for (int j = 0; j < name.length; j++) {
            if (stream[offset + j] != name[j]) {
                return false;
            }
        }
        return true;
    }
}
