package com.xavier.backend.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;

import com.xavier.backend.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Local development ({@code restaurantpro.dev.open-api-docs=true}, set by run-local.sh): the docs load before login,
 * while data endpoints still need a token. (Swagger UI still echoes an XSRF-TOKEN cookie, by the
 * {@code springdoc.swagger-ui.csrf} setting of application.yaml, which S72 may not edit; the API ignores it.)
 */
@SpringBootTest(properties = "restaurantpro.dev.open-api-docs=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ApiDocsOpenInDevTest {

    @Autowired
    MockMvc mvc;

    @Test
    void theApiDocsLoadWithoutATokenButDataStillNeedsOne() throws Exception {
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/backoffice/stores")).andExpect(status().isUnauthorized());
    }

    @Test
    void swaggerUiSendsTheCsrfTokenOnWrites() throws Exception {
        mvc.perform(get("/swagger-ui/swagger-initializer.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("XSRF-TOKEN")));
    }
}
