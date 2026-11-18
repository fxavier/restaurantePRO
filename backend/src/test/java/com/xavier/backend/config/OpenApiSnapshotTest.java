package com.xavier.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.shared.Money;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.core.util.Separators;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADR 0042 (gap G1): the frontend's typed client is generated from a committed snapshot of /v3/api-docs. This test
 * renders the document on the Spring context (no server; the docs are opened by the dev flag in this test only) and
 * fails when the committed snapshot differs, so a contract change shows up as a snapshot diff in the same PR. With
 * {@code -Dopenapi.snapshot=write} it rewrites the snapshot instead. Keys are sorted and indented so diffs are readable.
 */
@SpringBootTest(properties = "restaurantpro.dev.open-api-docs=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OpenApiSnapshotTest {

    static final Path SNAPSHOT = Path.of("..", "frontend", "packages", "api-client", "openapi.json");
    static final String WRITE_COMMAND = "rtk mvn test -Dtest=OpenApiSnapshotTest -Dopenapi.snapshot=write (from backend/)";

    private static final JsonMapper CANONICAL = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();
    /** The layout of JSON.stringify(value, null, 2), so frontend tools leave the file as it is. */
    private static final DefaultIndenter TWO_SPACES = new DefaultIndenter("  ", "\n");
    private static final DefaultPrettyPrinter LAYOUT = new DefaultPrettyPrinter(Separators.createDefaultInstance()
            .withObjectNameValueSpacing(Separators.Spacing.AFTER)
            .withObjectEmptySeparator("")
            .withArrayEmptySeparator(""))
            .withObjectIndenter(TWO_SPACES)
            .withArrayIndenter(TWO_SPACES);

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @Test
    void theCommittedSnapshotMatchesTheContract() throws Exception {
        String current = canonical(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        if ("write".equals(System.getProperty("openapi.snapshot"))) {
            Files.createDirectories(SNAPSHOT.getParent());
            Files.writeString(SNAPSHOT, current, StandardCharsets.UTF_8);
            return;
        }
        if (!Files.exists(SNAPSHOT)) {
            fail("The OpenAPI snapshot %s is missing. Write it with: %s", SNAPSHOT.normalize(), WRITE_COMMAND);
        }
        String committed = Files.readString(SNAPSHOT, StandardCharsets.UTF_8);
        if (!committed.equals(current)) {
            fail("The OpenAPI snapshot %s is stale: %s. Refresh it with: %s and commit the diff.",
                    SNAPSHOT.normalize(), firstDifference(committed, current), WRITE_COMMAND);
        }
    }

    /**
     * The headers the client must send are declared where they are read: Idempotency-Key and the approver's code and
     * PIN by the controllers as parameters. X-Pos-Session is read by the tenant filter on every POS operation after the
     * PIN login and the client's middleware adds it, so it is a security scheme, not a parameter each call must pass.
     */
    @Test
    void posOperationsDeclareTheHeadersTheyRead() throws Exception {
        JsonNode docs = apiDocs();

        JsonNode scheme = docs.at("/components/securitySchemes/posSession");
        assertThat(List.of(scheme.path("type").asString(), scheme.path("in").asString(), scheme.path("name").asString()))
                .containsExactly("apiKey", "header", "X-Pos-Session");
        assertThat(headers(docs, "/api/v1/pos/cash/movements", "post")).containsExactlyInAnyOrder(
                "Idempotency-Key!", "X-Approver-Code", "X-Approver-Pin");
        assertThat(security(docs, "/api/v1/pos/cash/movements", "post")).containsExactly("posSession");
        assertThat(security(docs, "/api/v1/auth/pos-session", "get")).containsExactly("posSession");
        assertThat(security(docs, "/api/v1/auth/pos-logout", "post")).containsExactly("posSession");
        assertThat(headers(docs, "/api/v1/auth/pos-session", "get")).isEmpty();
        assertThat(headers(docs, "/api/v1/auth/pos-logout", "post")).as("read by the controller too").isEmpty();
        assertThat(security(docs, "/api/v1/auth/pos-login", "post")).isEmpty();
        assertThat(security(docs, "/api/v1/pos/enrolment", "post")).isEmpty();
        assertThat(security(docs, "/api/v1/backoffice/stores", "get")).isEmpty();
    }

    /** Jackson writes a {@link Currency} as its ISO 4217 code, so the contract must type it as a string. */
    @Test
    void currencyIsDocumentedAsTheStringJacksonWrites() throws Exception {
        JsonNode money = json.readTree(json.writeValueAsString(Money.of("12.50", Currency.getInstance("MZN"))));
        assertThat(money.get("currency").isString()).as(money.toString()).isTrue();
        assertThat(money.get("currency").asString()).isEqualTo("MZN");

        JsonNode schema = apiDocs().at("/components/schemas/com.xavier.backend.shared.Money/properties/currency");
        assertThat(schema.toString()).isEqualTo("{\"type\":\"string\"}");
    }

    /**
     * FE-F18: the POS catalog's {@code catalog} is sent as the stored snapshot tree (its bytes are pinned by
     * PosCatalogHttpTest), but the contract describes it as the {@code CatalogSnapshot} record that wrote it, so the
     * client gets typed items, prices and menus instead of an untyped object.
     */
    @Test
    void posCatalogDocumentsItsSnapshotAsTheCatalogSnapshotRecord() throws Exception {
        JsonNode schemas = apiDocs().get("components").get("schemas");
        String dto = "com.xavier.backend.catalog.model.dto.";

        assertThat(schemas.at("/" + dto + "PosCatalogResponse/properties/catalog/$ref").asString())
                .isEqualTo("#/components/schemas/" + dto + "CatalogSnapshot");
        assertThat(schemas.at("/" + dto + "CatalogSnapshot/properties/items/items/$ref").asString())
                .isEqualTo("#/components/schemas/" + dto + "CatalogSnapshot.Item");
    }

    /** FE-F25: the generated client types the success branch by its status, so it must be the one sent. */
    @Test
    void operationsDocumentTheSuccessStatusTheySend() throws Exception {
        JsonNode docs = apiDocs();

        assertThat(docs.at("/paths/~1api~1v1~1pos~1orders~1{id}~1batches/post/responses").propertyNames())
                .containsExactly("201");
        assertThat(docs.at("/paths/~1api~1v1~1auth~1pos-logout/post/responses").propertyNames())
                .containsExactly("204");
    }

    private JsonNode apiDocs() throws Exception {
        return CANONICAL.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    /** The security schemes an operation requires, flattened over its alternatives. */
    private static List<String> security(JsonNode docs, String path, String method) {
        JsonNode operation = docs.get("paths").get(path).get(method);
        assertThat(operation).as(method + " " + path).isNotNull();
        List<String> schemes = new ArrayList<>();
        operation.path("security").forEach(requirement -> schemes.addAll(requirement.propertyNames()));
        return schemes;
    }

    /** The header parameters of an operation; a required one ends with "!". */
    private static List<String> headers(JsonNode docs, String path, String method) {
        JsonNode operation = docs.get("paths").get(path).get(method);
        assertThat(operation).as(method + " " + path).isNotNull();
        List<String> headers = new ArrayList<>();
        JsonNode parameters = operation.get("parameters");
        if (parameters != null) {
            parameters.forEach(p -> {
                if ("header".equals(p.get("in").asString())) {
                    headers.add(p.get("name").asString() + (p.path("required").asBoolean() ? "!" : ""));
                }
            });
        }
        return headers;
    }

    private static String canonical(String json) {
        Object tree = CANONICAL.readValue(json, Object.class);
        return CANONICAL.writer().with(LAYOUT).writeValueAsString(tree) + "\n";
    }

    private static String firstDifference(String committed, String current) {
        List<String> before = committed.lines().toList();
        List<String> after = current.lines().toList();
        for (int i = 0; i < Math.min(before.size(), after.size()); i++) {
            if (!before.get(i).equals(after.get(i))) {
                return "first difference at line %d, committed [%s], code [%s]"
                        .formatted(i + 1, before.get(i).strip(), after.get(i).strip());
            }
        }
        return "committed has %d lines, code has %d".formatted(before.size(), after.size());
    }
}
