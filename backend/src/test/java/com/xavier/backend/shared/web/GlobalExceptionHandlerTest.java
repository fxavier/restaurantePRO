package com.xavier.backend.shared.web;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.shared.exception.BusinessRuleException;
import com.xavier.backend.shared.exception.ConflictException;
import com.xavier.backend.shared.exception.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, GlobalExceptionHandlerTest.ProbeController.class})
@WithMockUser
class GlobalExceptionHandlerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void notFoundBecomes404ProblemDetail() throws Exception {
        mvc.perform(get("/api/v1/backoffice/test-probe/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Store 42 not found"));
    }

    @Test
    void stateConflictBecomes409() throws Exception {
        mvc.perform(get("/api/v1/backoffice/test-probe/conflict"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Release is not VALIDATED"));
    }

    @Test
    void staleVersionBecomes409() throws Exception {
        mvc.perform(get("/api/v1/backoffice/test-probe/stale-version"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void brokenBusinessRuleBecomes422() throws Exception {
        mvc.perform(get("/api/v1/backoffice/test-probe/business-rule"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Currency cannot change after the first store"));
    }

    @Test
    void invalidRequestBodyBecomes400WithFieldErrors() throws Exception {
        mvc.perform(post("/api/v1/backoffice/test-probe/validated").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\": \"\", \"seats\": 0}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("code", "seats")));
    }

    @Test
    void unexpectedErrorBecomes500WithoutLeakingInternals() throws Exception {
        mvc.perform(get("/api/v1/backoffice/test-probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail", not(containsString("jdbc"))));
    }

    record ProbeRequest(@NotBlank String code, @Positive int seats) {
    }

    @RestController
    @RequestMapping("/api/v1/backoffice/test-probe")
    static class ProbeController {

        @GetMapping("/not-found")
        void notFound() {
            throw new NotFoundException("Store 42 not found");
        }

        @GetMapping("/conflict")
        void conflict() {
            throw new ConflictException("Release is not VALIDATED");
        }

        @GetMapping("/stale-version")
        void staleVersion() {
            throw new ObjectOptimisticLockingFailureException("Store", 42);
        }

        @GetMapping("/business-rule")
        void businessRule() {
            throw new BusinessRuleException("Currency cannot change after the first store");
        }

        @PostMapping("/validated")
        void validated(@Valid @RequestBody ProbeRequest request) {
        }

        @GetMapping("/boom")
        void boom() {
            throw new IllegalStateException("jdbc:postgresql://internal-host leaked");
        }
    }
}
