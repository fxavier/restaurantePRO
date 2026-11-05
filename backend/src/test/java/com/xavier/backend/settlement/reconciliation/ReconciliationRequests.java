package com.xavier.backend.settlement.reconciliation;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.cash.CashFixtures;
import com.xavier.backend.settlement.SettlementFixtures;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * The reconciliation requests the tests send (a BackOffice account with the reconciliation permissions in the store),
 * and the payments they reconcile, built through the settlement fixtures. Plain static helpers: the tests run in the
 * cached settlement context.
 */
public final class ReconciliationRequests {

    public static final String BASE = "/api/v1/backoffice/reconciliation";
    public static final String HEADER = "occurred_at,amount,fee,reference,authorization_code,line_ref\n";
    public static final ZoneId STORE_ZONE = ZoneId.of("Africa/Maputo");

    private ReconciliationRequests() {
    }

    public static ResultActions importCsv(MockMvc mvc, UUID store, String method, String csv, String key)
            throws Exception {
        return importCsv(mvc, store, method, csv, key, Permission.RECONCILIATION_MANAGE,
                Permission.RECONCILIATION_VIEW);
    }

    public static ResultActions importCsv(MockMvc mvc, UUID store, String method, String csv, String key,
                                          Permission... permissions) throws Exception {
        LocalDate today = LocalDate.now(STORE_ZONE);
        return mvc.perform(multipart(BASE + "/batches")
                .file(new MockMultipartFile("file", "statement.csv", "text/csv",
                        (HEADER + csv).getBytes(StandardCharsets.UTF_8)))
                .param("storeId", store.toString()).param("methodCode", method)
                .param("periodFrom", today.minusDays(1).toString()).param("periodTo", today.plusDays(1).toString())
                .header("Idempotency-Key", key).with(csrf()).with(CashFixtures.account(store, permissions)));
    }

    public static ResultActions entries(MockMvc mvc, UUID store, UUID batch, String query) throws Exception {
        return mvc.perform(get(BASE + "/batches/" + batch + "/entries" + query)
                .with(CashFixtures.account(store, Permission.RECONCILIATION_VIEW)));
    }

    public static ResultActions resolve(MockMvc mvc, UUID store, UUID entry, String action, String reason)
            throws Exception {
        return mvc.perform(post(BASE + "/entries/" + entry + "/resolutions")
                .header("Idempotency-Key", UUID.randomUUID().toString()).with(csrf())
                .with(CashFixtures.account(store, Permission.RECONCILIATION_MANAGE))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\": \"%s\", \"reason\": \"%s\"}".formatted(action, reason)));
    }

    /** A new EXTERNAL_TERMINAL method (a card terminal); returns its code. */
    public static String cardMethod(SettlementFixtures fixtures) throws Exception {
        String code = SettlementFixtures.freshCode("CARD");
        fixtures.method(code, "EXTERNAL_TERMINAL", null);
        return code;
    }

    /** A card payment of {@code amount} on the sale, confirmed with {@code authorizationCode}; returns the payment. */
    public static UUID cardPayment(SettlementFixtures fixtures, Sale sale, String method, String amount,
                                   String authorizationCode) throws Exception {
        UUID attempt = SettlementFixtures.id(fixtures.pay(sale.cashier(), sale.order(), method, amount, null,
                UUID.randomUUID().toString()).andExpect(status().isCreated()));
        fixtures.confirm(sale.cashier(), attempt, authorizationCode).andExpect(status().isOk());
        return fixtures.paymentOfAttempt(attempt);
    }

    public static Instant confirmedAt(JdbcClient jdbc, UUID payment) {
        return jdbc.sql("SELECT confirmed_at FROM settlement.payment WHERE id = ?").param(payment)
                .query(java.time.OffsetDateTime.class).single().toInstant();
    }

    /** One canonical CSV line. */
    public static String line(Instant at, String amount, String fee, String reference, String authorization,
                              String lineRef) {
        return "%s,%s,%s,%s,%s,%s\n".formatted(at, amount, fee == null ? "" : fee, nz(reference), nz(authorization),
                nz(lineRef));
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    public static JsonNode json(ResultActions result) throws Exception {
        return SettlementFixtures.json(result);
    }

    public static BigDecimal decimal(JsonNode node) {
        return node == null || node.isNull() ? null : new BigDecimal(node.asString());
    }
}
