package com.xavier.backend.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.xavier.backend.shared.web.GlobalExceptionHandler.FieldViolation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.InvalidDataAccessApiUsageException;
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

    @Autowired
    io.micrometer.core.instrument.MeterRegistry registry;

    @Autowired
    com.xavier.backend.shared.persistence.StoreScopeViolations violations;

    @Test
    void aRowLevelSecurityRefusalIsCountedAndRemembered() throws Exception {
        double before = registry.get("store_scope_violations").tag("kind", "store_change").counter().count();

        mvc.perform(get("/api/v1/backoffice/test-probe/store-change"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("STORE_CHANGE_REFUSED"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("order_header"))));

        assertThat(registry.get("store_scope_violations").tag("kind", "store_change").counter().count())
                .isEqualTo(before + 1);
        assertThat(violations.lastDay()).containsKey(
                com.xavier.backend.shared.persistence.StoreScopeViolations.Kind.STORE_CHANGE);
    }

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
    void aCodedBusinessRuleCarriesItsCodeAndExtraProperties() throws Exception {
        mvc.perform(get("/api/v1/backoffice/test-probe/coded-business-rule"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("The fiscal document stays pending"))
                .andExpect(jsonPath("$.code").value("FISCAL_PENDING"))
                .andExpect(jsonPath("$.documentId").value("0190a000-0000-7000-8000-0000000000d1"))
                .andExpect(jsonPath("$.errors[0].field").value("price"));
    }

    @Test
    void aCodedConflictCarriesItsCode() throws Exception {
        mvc.perform(get("/api/v1/backoffice/test-probe/coded-conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Table 7 is occupied."))
                .andExpect(jsonPath("$.code").value("TABLE_OCCUPIED"));
    }

    @Test
    void aCodedNotFoundCarriesItsCode() throws Exception {
        mvc.perform(get("/api/v1/backoffice/test-probe/coded-not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No published catalog"))
                .andExpect(jsonPath("$.code").value("NO_PUBLISHED_CATALOG"));
    }

    @Test
    void anUncodedErrorHasNoCodeProperty() throws Exception {
        mvc.perform(get("/api/v1/backoffice/test-probe/business-rule"))
                .andExpect(jsonPath("$.code").doesNotExist());
        mvc.perform(get("/api/v1/backoffice/test-probe/conflict"))
                .andExpect(jsonPath("$.code").doesNotExist());
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

    @Test
    void aDataAccessMisuseThatIsNotAnUnknownSortPathStaysA500() throws Exception {
        mvc.perform(get("/api/v1/backoffice/test-probe/data-access-misuse"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("Unexpected error."));
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

        @GetMapping("/coded-business-rule")
        void codedBusinessRule() {
            throw new BusinessRuleException("FISCAL_PENDING", "The fiscal document stays pending",
                    Map.of("documentId", UUID.fromString("0190a000-0000-7000-8000-0000000000d1"),
                            "errors", List.of(new FieldViolation("price", "must be positive"))));
        }

        @GetMapping("/coded-conflict")
        void codedConflict() {
            throw new ConflictException("TABLE_OCCUPIED", "Table 7 is occupied.");
        }

        @GetMapping("/coded-not-found")
        void codedNotFound() {
            throw new NotFoundException("NO_PUBLISHED_CATALOG", "No published catalog");
        }

        @PostMapping("/validated")
        void validated(@Valid @RequestBody ProbeRequest request) {
        }

        @GetMapping("/data-access-misuse")
        void dataAccessMisuse() {
            throw new InvalidDataAccessApiUsageException("a programming error");
        }

        @GetMapping("/store-change")
        void storeChange() {
            throw new org.springframework.dao.DataAccessResourceFailureException("could not execute statement",
                    new java.sql.SQLException("store change refused on orders.order_header (origin listener:X.on)",
                            "RS002"));
        }

        @GetMapping("/boom")
        void boom() {
            throw new IllegalStateException("jdbc:postgresql://internal-host leaked");
        }
    }
}
