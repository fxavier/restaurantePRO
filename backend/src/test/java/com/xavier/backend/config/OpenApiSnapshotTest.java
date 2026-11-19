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
     * FE-F33: Jackson writes every BigDecimal as a string holding the plain decimal, so the contract types it as a
     * string (format decimal) and no property is left as a JSON number the client would parse with lost precision.
     */
    @Test
    void decimalsAreDocumentedAsTheStringsJacksonWrites() throws Exception {
        JsonNode money = json.readTree(json.writeValueAsString(Money.of("12.50", Currency.getInstance("MZN"))));
        assertThat(money.get("amount").isString()).as(money.toString()).isTrue();
        assertThat(money.get("amount").asString()).isEqualTo("12.50");

        JsonNode docs = apiDocs();
        JsonNode amount = docs.at("/components/schemas/com.xavier.backend.shared.Money/properties/amount");
        assertThat(amount.toString()).isEqualTo("{\"type\":\"string\",\"format\":\"decimal\"}");
        assertThat(docs.toString()).doesNotContain("\"type\":\"number\"");
    }

    /**
     * FE-F28: a {@code @NullMarked} response record lists every component as required (Jackson always writes it) and
     * lets only its {@code @Nullable} components be null: {@code type} gains {@code "null"}, a reference or an enum
     * becomes {@code oneOf} it and {@code null}. A component an old stored document may lack is not required. Request
     * records keep their Bean Validation {@code required}.
     */
    @Test
    void nullMarkedRecordsDocumentRequiredAndNullableComponents() throws Exception {
        JsonNode schemas = apiDocs().get("components").get("schemas");
        String organization = "com.xavier.backend.organization.model.dto.";

        JsonNode enrolment = schemas.get(organization + "DeviceEnrolmentResponse");
        assertThat(texts(enrolment.get("required"))).containsExactlyInAnyOrder("id", "storeId", "clientId");
        assertThat(enrolment.get("properties").toString()).doesNotContain("null");

        JsonNode table = schemas.get("com.xavier.backend.dining.model.dto.TableMap.Table");
        assertThat(texts(table.get("required"))).hasSize(12);
        assertThat(table.at("/properties/openedAt"))
                .isEqualTo(tree("{\"format\":\"date-time\",\"type\":[\"string\",\"null\"]}"));
        assertThat(table.at("/properties/seats/type").asString()).isEqualTo("integer");

        JsonNode order = schemas.get("com.xavier.backend.orders.model.dto.OrderView");
        String money = "{\"$ref\":\"#/components/schemas/com.xavier.backend.shared.Money\"}";
        assertThat(order.at("/properties/draftGross"))
                .isEqualTo(tree("{\"oneOf\":[" + money + ",{\"type\":\"null\"}]}"));
        assertThat(order.at("/properties/due")).isEqualTo(tree(money));
        assertThat(order.at("/properties/mode/type").asString()).isEqualTo("string");

        JsonNode line = schemas.get("com.xavier.backend.orders.model.dto.LineView");
        assertThat(line.at("/properties/kind/oneOf/1")).isEqualTo(tree("{\"type\":\"null\"}"));

        JsonNode catalog = schemas.get("com.xavier.backend.catalog.model.dto.CatalogSnapshot");
        assertThat(texts(catalog.get("required"))).contains("schemaVersion", "items")
                .as("a version-1 document lacks them").doesNotContain("quickPages", "macros", "automaticItems");

        assertThat(texts(schemas.get(organization + "TerminalCreateRequest").get("required")))
                .as("a request keeps its Bean Validation required").containsExactlyInAnyOrder("code", "name", "storeId");
    }

    /**
     * FE-F28: JSON Schema's {@code minimum} does not apply to a string, so the Bean Validation bounds of a decimal
     * string are documented as {@code x-minimum}, {@code x-exclusiveMinimum}, {@code x-maximum} and
     * {@code x-exclusiveMaximum}, each the exact decimal bound as a string.
     */
    @Test
    void decimalBoundsAreDocumentedOnTheDecimalStrings() throws Exception {
        JsonNode schemas = apiDocs().get("components").get("schemas");

        assertThat(schemas.at("/com.xavier.backend.cash.model.dto.AdjustmentRequest/properties/amount"))
                .isEqualTo(tree("{\"format\":\"decimal\",\"type\":\"string\",\"x-exclusiveMinimum\":\"0\"}"));
        assertThat(schemas.at("/com.xavier.backend.orders.model.dto.ConfirmBatchRequest/properties/expectedGross"))
                .isEqualTo(tree("{\"format\":\"decimal\",\"type\":\"string\",\"x-minimum\":\"0\"}"));
        assertThat(schemas.at("/com.xavier.backend.orders.model.dto.DiscountRequest/properties/percent"))
                .isEqualTo(tree("{\"format\":\"decimal\",\"type\":\"string\",\"x-exclusiveMinimum\":\"0\","
                        + "\"x-maximum\":\"100\"}"));
        List<String> numericBoundsOnStrings = new ArrayList<>();
        schemas.forEach(schema -> schema.path("properties").forEach(property -> {
            if (property.path("type").toString().contains("\"string\"")
                    && (property.has("minimum") || property.has("exclusiveMinimum") || property.has("maximum"))) {
                numericBoundsOnStrings.add(property.toString());
            }
        }));
        assertThat(numericBoundsOnStrings).isEmpty();
    }

    private JsonNode tree(String json) {
        return this.json.readTree(json);
    }

    private static List<String> texts(JsonNode array) {
        assertThat(array).as("required").isNotNull();
        List<String> texts = new ArrayList<>();
        array.forEach(node -> texts.add(node.asString()));
        return texts;
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

        for (String created : List.of("/api/v1/pos/orders", "/api/v1/pos/orders/{id}/batches",
                "/api/v1/pos/orders/{id}/lines", "/api/v1/pos/orders/{id}/lines/macro",
                "/api/v1/pos/tables/{tableId}/open", "/api/v1/backoffice/terminals")) {
            assertThat(responses(docs, created, "post")).as("POST " + created).containsExactly("201");
        }
        assertThat(responses(docs, "/api/v1/pos/orders/{id}/lines/{lineId}", "delete")).containsExactly("204");
        assertThat(responses(docs, "/api/v1/auth/pos-logout", "post")).containsExactly("204");
    }

    /**
     * FE-F11: Spring binds a Pageable (and the audit filter) from flat query parameters, so the contract must list
     * {@code page}, {@code size} and {@code sort}, not one {@code pageable} object the client would have to serialise
     * flat by hand. They carry {@code @ParameterObject}; the global {@code default-flat-param-object} would also
     * flatten the {@code @AuthenticationPrincipal Jwt} of GET /auth/tenants into query parameters.
     */
    @Test
    void pagedListsDocumentFlatPageAndSizeParameters() throws Exception {
        JsonNode docs = apiDocs();

        for (String paged : List.of("/api/v1/backoffice/stores", "/api/v1/backoffice/terminals")) {
            assertThat(queryParameters(docs, paged, "get")).as("GET " + paged)
                    .contains("page", "size", "sort").doesNotContain("pageable");
        }
        assertThat(queryParameters(docs, "/api/v1/backoffice/audit/events", "get"))
                .contains("page", "size").doesNotContain("pageable", "filter");
        assertThat(queryParameters(docs, "/api/v1/auth/tenants", "get"))
                .as("the @AuthenticationPrincipal token is not a parameter").isEmpty();
    }

    private static List<String> queryParameters(JsonNode docs, String path, String method) {
        JsonNode operation = docs.get("paths").get(path).get(method);
        assertThat(operation).as(method + " " + path).isNotNull();
        List<String> names = new ArrayList<>();
        operation.path("parameters").forEach(p -> {
            if ("query".equals(p.get("in").asString())) {
                names.add(p.get("name").asString());
            }
        });
        return names;
    }

    private static List<String> responses(JsonNode docs, String path, String method) {
        JsonNode operation = docs.get("paths").get(path).get(method);
        assertThat(operation).as(method + " " + path).isNotNull();
        return new ArrayList<>(operation.get("responses").propertyNames());
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
