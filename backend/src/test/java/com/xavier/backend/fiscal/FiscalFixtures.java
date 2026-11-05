package com.xavier.backend.fiscal;

import static com.xavier.backend.fiscal.FiscalActors.admin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.fiscal.model.dto.Buyer;
import com.xavier.backend.fiscal.model.dto.DraftCommand;
import com.xavier.backend.fiscal.model.dto.DraftLine;
import com.xavier.backend.fiscal.model.dto.SourceType;
import com.xavier.backend.fiscal.model.dto.SaleDraftCommand;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/** Stores (through the organization HTTP API, as an installation admin would) and audit reads for fiscal tests. */
@TestComponent
public class FiscalFixtures {

    public static final Currency MZN = Currency.getInstance("MZN");
    public static final JsonMapper JSON = JsonMapper.builder().build();

    private final MockMvc mvc;
    private final JdbcClient jdbc;
    private final FiscalApi fiscal;
    private final TransactionTemplate tx;

    FiscalFixtures(MockMvc mvc, JdbcClient jdbc, FiscalApi fiscal, TransactionTemplate tx) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.fiscal = fiscal;
        this.tx = tx;
    }

    public UUID newSeries(UUID store, String code, long firstNumber) throws Exception {
        return id(mvc.perform(post("/api/v1/backoffice/fiscal/series").with(admin(Permission.FISCAL_MANAGE))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"storeId": "%s", "code": "%s", "firstNumber": %d}""".formatted(store, code, firstNumber)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    /** A SALE draft for a new order, committed (tx-A). */
    public UUID newDraft(UUID store) {
        return tx.execute(s -> fiscal.createSaleDraft(saleDraft(store, UUID.randomUUID(), null))).id();
    }

    /** {@code action} as a POS employee on {@code terminal}, on the current thread (issue reads the terminal). */
    public static <T> T asEmployee(UUID store, UUID employee, UUID terminal, Supplier<T> action) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(FiscalActors.employee(store, employee, terminal, Permission.PAYMENT_COLLECT));
        SecurityContextHolder.setContext(context);
        try {
            return action.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    public UUID sourceOf(UUID document) {
        return jdbc.sql("SELECT source_id FROM fiscal.fiscal_document WHERE id = :id").param("id", document)
                .query(UUID.class).single();
    }

    public String adapterData(UUID document) {
        return jdbc.sql("SELECT adapter_data FROM fiscal.fiscal_document WHERE id = :id").param("id", document)
                .query(String.class).single();
    }

    public long nextNumber(UUID series) {
        return jdbc.sql("SELECT next_number FROM fiscal.fiscal_series WHERE id = :id").param("id", series)
                .query(Long.class).single();
    }

    public UUID newStore() throws Exception {
        String code = "F" + UUID.randomUUID().toString().substring(0, 8);
        return id(mvc.perform(post("/api/v1/backoffice/stores").with(admin(Permission.STORE_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "%s", "name": "Loja Fiscal", "timezone": "Africa/Maputo"}""".formatted(code)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    public long audits(String action, String entityId) {
        return jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE action = :action AND entity_id = :entity")
                .param("action", action).param("entity", entityId).query(Long.class).single();
    }

    /** One line, 2 x 58.00 at 16 % (gross 116.00 = net 100.00 + tax 16.00), in the company currency (MZN). */
    public static SaleDraftCommand saleDraft(UUID store, UUID order, Buyer buyer) {
        DraftLine beer = new DraftLine(UUID.randomUUID(), "Cerveja 2M", new BigDecimal("2"), mzn("58.00"),
                new BigDecimal("16"), mzn("116.00"), mzn("100.00"), mzn("16.00"));
        return new SaleDraftCommand(store, order, buyer, List.of(beer), mzn("116.00"), mzn("100.00"), mzn("16.00"));
    }

    /**
     * The same line as {@link #saleDraft}, for any source, on business day 2026-11-04. The source line id is derived
     * from the source, so drafting the same source again is the same content.
     */
    public static DraftCommand draft(UUID store, SourceType sourceType, UUID source, UUID order, Buyer buyer,
                                     UUID customer, boolean onAccount) {
        SaleDraftCommand sale = saleDraft(store, source, buyer);
        DraftLine line = sale.lines().getFirst();
        DraftLine stable = new DraftLine(UUID.nameUUIDFromBytes(("line-of-" + source).getBytes()), line.description(),
                line.quantity(), line.unitPrice(), line.taxRatePercent(), line.gross(), line.net(), line.tax());
        return new DraftCommand(store, sourceType, source, order, LocalDate.of(2026, 11, 4), buyer, customer,
                onAccount, List.of(stable), sale.totalGross(), sale.totalNet(), sale.totalTax());
    }

    public static Money mzn(String amount) {
        return Money.of(amount, MZN);
    }

    public static UUID id(String json) {
        return UUID.fromString(JSON.readTree(json).get("id").asString());
    }
}
