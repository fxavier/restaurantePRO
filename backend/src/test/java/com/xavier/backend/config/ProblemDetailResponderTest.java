package com.xavier.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.access.StepUpRequiredException;
import com.xavier.backend.shared.web.GlobalExceptionHandler;
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
 * The filter-chain 403 renders a D142 step-up refusal with its code, like the controller path does. Uses the
 * application's auto-configured {@link JsonMapper}, which flattens ProblemDetail properties.
 */
class ProblemDetailResponderTest {

    final JsonMapper json = bootJsonMapper();
    final ProblemDetailResponder responder = new ProblemDetailResponder(json);

    private static JsonMapper bootJsonMapper() {
        JsonMapper[] mapper = new JsonMapper[1];
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .run(context -> mapper[0] = context.getBean(JsonMapper.class));
        return mapper[0];
    }

    @Test
    void aStepUpRefusalInTheFilterChainCarriesItsCodeAndTheStepUpUri() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        responder.forbidden().handle(new MockHttpServletRequest(), response,
                StepUpRequiredException.enrolmentRequired());

        JsonNode body = json.readTree(response.getContentAsString());
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(body.get("code").asString()).isEqualTo("MFA_ENROLMENT_REQUIRED");
        assertThat(body.get("stepUpUri").asString()).isEqualTo("/api/v1/auth/mfa/step-up");
        assertThat(body.get("detail").asString()).isEqualTo("Set up a second factor to continue.");
    }

    /** shared's handler maps every AccessDeniedException to a plain 403; this advice must be asked first. */
    @Test
    void theAdviceIsOrderedBeforeTheGlobalExceptionHandler() {
        assertThat(OrderUtils.getOrder(ProblemDetailResponder.class, Ordered.LOWEST_PRECEDENCE))
                .isLessThan(OrderUtils.getOrder(GlobalExceptionHandler.class, Ordered.LOWEST_PRECEDENCE));
    }

    @Test
    void aPosSessionRefusalInTheFilterChainHasNoStepUpUri() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        responder.forbidden().handle(new MockHttpServletRequest(), response, StepUpRequiredException.accountRequired());

        JsonNode body = json.readTree(response.getContentAsString());
        assertThat(body.get("code").asString()).isEqualTo("MFA_ACCOUNT_REQUIRED");
        assertThat(body.has("stepUpUri")).isFalse();
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
