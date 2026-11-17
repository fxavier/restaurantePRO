package com.xavier.backend.config.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.access.StepUpRequiredException;
import com.xavier.backend.shared.web.GlobalExceptionHandler;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.OrderUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The filter chain renders a step-up refusal (D184) like the controller path does: the RFC 9470 challenge. Uses the
 * application's auto-configured {@link JsonMapper}, which flattens ProblemDetail properties.
 */
class ProblemDetailResponderTest {

    final JsonMapper json = bootJsonMapper();
    final ProblemDetailResponder responder = new ProblemDetailResponder(json, new SecurityProperties(null,
            "restaurantpro-api", Duration.ofSeconds(30), Duration.ofMinutes(5)));

    private static JsonMapper bootJsonMapper() {
        JsonMapper[] mapper = new JsonMapper[1];
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .run(context -> mapper[0] = context.getBean(JsonMapper.class));
        return mapper[0];
    }

    @Test
    void aStepUpRefusalInTheFilterChainIsTheRfc9470Challenge() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        responder.forbidden().handle(new MockHttpServletRequest(), response,
                StepUpRequiredException.stepUpRequired());

        JsonNode body = json.readTree(response.getContentAsString());
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer error=\"insufficient_user_authentication\", "
                + "error_description=\"A different authentication level is required\", acr_values=\"2\", max_age=300");
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(body.get("code").asString()).isEqualTo("MFA_STEP_UP_REQUIRED");
        assertThat(body.get("maxAge").asLong()).isEqualTo(300);
    }

    /** shared's handler maps every AccessDeniedException to a plain 403; this advice must be asked first. */
    @Test
    void theAdviceIsOrderedBeforeTheGlobalExceptionHandler() {
        assertThat(OrderUtils.getOrder(ProblemDetailResponder.class, Ordered.LOWEST_PRECEDENCE))
                .isLessThan(OrderUtils.getOrder(GlobalExceptionHandler.class, Ordered.LOWEST_PRECEDENCE));
    }

    @Test
    void aPosSessionRefusalInTheFilterChainIsA403WithoutChallenge() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        responder.forbidden().handle(new MockHttpServletRequest(), response, StepUpRequiredException.accountRequired());

        JsonNode body = json.readTree(response.getContentAsString());
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(body.get("code").asString()).isEqualTo("MFA_ACCOUNT_REQUIRED");
        assertThat(response.getHeader("WWW-Authenticate")).isNull();
    }

    @Test
    void anyOtherDenialKeepsThePlainShape() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        responder.forbidden().handle(new MockHttpServletRequest(), response, new AccessDeniedException("x"));

        JsonNode body = json.readTree(response.getContentAsString());
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(body.has("code")).isFalse();
        assertThat(body.get("detail").asString()).isEqualTo("You are not allowed to do this.");
    }
}
