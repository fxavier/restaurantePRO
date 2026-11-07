package com.xavier.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.access.WithActor;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * D10: /v3/api-docs is the source of the frontend client, so two records with the same simple name in different
 * modules (customers and delivery {@code AddressRequest}, catalog and loyalty {@code PublishRequest}) must stay two
 * schemas; springdoc merges them by simple name otherwise.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OpenApiSchemaNamesTest {

    @Autowired
    MockMvc mvc;

    @Test
    @WithActor
    void requestSchemasWithTheSameSimpleNameStayApart() throws Exception {
        JsonNode docs = JsonMapper.shared().readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        JsonNode customerAddress = requestSchema(docs, "/api/v1/backoffice/customers/{id}/addresses");
        assertThat(names(customerAddress.get("required"))).contains("kind", "line1");

        JsonNode catalogPublish = requestSchema(docs, "/api/v1/backoffice/catalog/releases/{id}/publish");
        assertThat(names(catalogPublish.get("properties").propertyNames())).containsExactly("note");
    }

    private static JsonNode requestSchema(JsonNode docs, String path) {
        JsonNode operation = docs.get("paths").get(path).get("post");
        assertThat(operation).as(path).isNotNull();
        String ref = operation.get("requestBody").get("content").get("application/json").get("schema").get("$ref")
                .asString();
        return docs.get("components").get("schemas").get(ref.substring(ref.lastIndexOf('/') + 1));
    }

    private static List<String> names(Iterable<?> values) {
        List<String> names = new ArrayList<>();
        values.forEach(v -> names.add(v instanceof JsonNode node ? node.asString() : v.toString()));
        return names;
    }
}
