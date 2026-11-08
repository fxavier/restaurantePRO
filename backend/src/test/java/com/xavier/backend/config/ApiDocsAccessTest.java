package com.xavier.backend.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** By default (every installation) Swagger UI and the OpenAPI document need a session like any other path. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ApiDocsAccessTest {

    @Autowired
    MockMvc mvc;

    @Test
    void theApiDocsNeedASessionByDefault() throws Exception {
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
    }
}
