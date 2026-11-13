package com.xavier.backend.production.controller;

import static com.xavier.backend.fiscal.FiscalActors.inStore;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.fiscal.FiscalFixtures;
import com.xavier.backend.production.ProductionFixtures;
import com.xavier.backend.production.ProductionModuleTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * D154 through the BackOffice: a printer's profile is created with the defaults when omitted, edited with the
 * printer, refused with 422 {@code PRINTER_PROFILE_INVALID} outside its ranges (also CHECKs in V7008), and the three
 * presets are listed (P60: CP850 offered).
 */
@ProductionModuleTest
class PrinterProfileValidationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ProductionFixtures fixtures;

    @Autowired
    FiscalFixtures stores;

    @Autowired
    JdbcClient jdbc;

    UUID store;

    @BeforeEach
    void store() throws Exception {
        store = stores.newStore();
    }

    @Test
    void aPrinterCreatedWithoutAProfileGetsTheDefaults() throws Exception {
        fixtures.createPrinter(store, "KIT", "10.0.0.5", 9100).andExpect(status().isCreated())
                .andExpect(jsonPath("$.profile.paperWidthMm").value(80))
                .andExpect(jsonPath("$.profile.columns").value(48))
                .andExpect(jsonPath("$.profile.codeTable").isEmpty())
                .andExpect(jsonPath("$.profile.cutMode").value("PARTIAL"))
                .andExpect(jsonPath("$.profile.feedLines").value(4))
                .andExpect(jsonPath("$.profile.statusProbe").value(false))
                .andExpect(jsonPath("$.profile.drawerPin").isEmpty())
                .andExpect(jsonPath("$.profile.drawerPulseMs").value(100))
                .andExpect(jsonPath("$.lastStatus").isEmpty()).andExpect(jsonPath("$.lastStatusAt").isEmpty());
    }

    @Test
    void aFiftyEightMillimetrePrinterWithoutColumnsGets32() throws Exception {
        create("""
                "paperWidthMm": 58, "cutMode": "NONE\"""").andExpect(status().isCreated())
                .andExpect(jsonPath("$.profile.columns").value(32))
                .andExpect(jsonPath("$.profile.cutMode").value("NONE"));
    }

    @Test
    void aFullProfileIsStoredListedAndEdited() throws Exception {
        UUID printer = ProductionFixtures.id(create(FULL_PROFILE).andExpect(status().isCreated()));

        mvc.perform(get(ProductionFixtures.PRINTERS).param("storeId", store.toString())
                        .with(inStore(store, Permission.PRODUCTION_MANAGE))).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].profile.columns").value(42))
                .andExpect(jsonPath("$.content[0].profile.codeTable").value(17))
                .andExpect(jsonPath("$.content[0].profile.cutMode").value("FULL"))
                .andExpect(jsonPath("$.content[0].profile.feedLines").value(6))
                .andExpect(jsonPath("$.content[0].profile.statusProbe").value(true))
                .andExpect(jsonPath("$.content[0].profile.drawerPin").value(1))
                .andExpect(jsonPath("$.content[0].profile.drawerPulseMs").value(120));
        update(printer, """
                "paperWidthMm": 58, "columns": 30, "cutMode": "PARTIAL\"""").andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.paperWidthMm").value(58))
                .andExpect(jsonPath("$.profile.columns").value(30))
                .andExpect(jsonPath("$.profile.cutMode").value("PARTIAL"))
                .andExpect(jsonPath("$.profile.codeTable").value(17))
                .andExpect(jsonPath("$.profile.feedLines").value(6))
                .andExpect(jsonPath("$.profile.statusProbe").value(true))
                .andExpect(jsonPath("$.profile.drawerPin").value(1))
                .andExpect(jsonPath("$.profile.drawerPulseMs").value(120));
    }

    /** PRD S59 (amended 2026-11-13): a PUT keeps every profile field it does not send. */
    @Test
    void aPutThatSendsOnlyThePrinterFieldsLeavesTheProfileUnchanged() throws Exception {
        UUID printer = ProductionFixtures.id(create(FULL_PROFILE).andExpect(status().isCreated()));

        update(printer, "").andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Cozinha"))
                .andExpect(jsonPath("$.profile.paperWidthMm").value(80))
                .andExpect(jsonPath("$.profile.columns").value(42))
                .andExpect(jsonPath("$.profile.codeTable").value(17))
                .andExpect(jsonPath("$.profile.cutMode").value("FULL"))
                .andExpect(jsonPath("$.profile.feedLines").value(6))
                .andExpect(jsonPath("$.profile.statusProbe").value(true))
                .andExpect(jsonPath("$.profile.drawerPin").value(1))
                .andExpect(jsonPath("$.profile.drawerPulseMs").value(120));
    }

    /** An explicit null clears the optional table override and drawer pin; the rest is kept. */
    @Test
    void anExplicitNullClearsTheCodeTableAndTheDrawerPin() throws Exception {
        UUID printer = ProductionFixtures.id(create(FULL_PROFILE).andExpect(status().isCreated()));

        update(printer, """
                "codeTable": null, "drawerPin": null""").andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.codeTable").isEmpty())
                .andExpect(jsonPath("$.profile.drawerPin").isEmpty())
                .andExpect(jsonPath("$.profile.columns").value(42))
                .andExpect(jsonPath("$.profile.drawerPulseMs").value(120));
    }

    static final String FULL_PROFILE = """
            "paperWidthMm": 80, "columns": 42, "codeTable": 17, "cutMode": "FULL", "feedLines": 6,
            "statusProbe": true, "drawerPin": 1, "drawerPulseMs": 120""";

    @Test
    void columnsThatDoNotFitThePaperWidthAreRefused() throws Exception {
        expectInvalid(create("""
                "paperWidthMm": 58, "columns": 48"""));
        expectInvalid(create("""
                "paperWidthMm": 80, "columns": 32"""));
        UUID printer = fixtures.printer(store, "KIT", "10.0.0.5", 9100);
        expectInvalid(update(printer, """
                "paperWidthMm": 58, "columns": 36"""));
    }

    @Test
    void anOddOrOutOfRangeDrawerPulseIsRefused() throws Exception {
        expectInvalid(create("""
                "drawerPulseMs": 101"""));
        expectInvalid(create("""
                "drawerPulseMs": 600"""));
    }

    @Test
    void aWidthOtherThan58Or80IsRefused() throws Exception {
        expectInvalid(create("""
                "paperWidthMm": 76"""));
    }

    @Test
    void theDatabaseRefusesAProfileOutsideItsRanges() throws Exception {
        UUID printer = fixtures.printer(store, "KIT", "10.0.0.5", 9100);

        assertRefused(printer, "columns = 30");
        assertRefused(printer, "paper_width_mm = 58");
        assertRefused(printer, "code_table = 256");
        assertRefused(printer, "cut_mode = 'HALF'");
        assertRefused(printer, "feed_lines = 11");
        assertRefused(printer, "drawer_pin = 2");
        assertRefused(printer, "drawer_pulse_ms = 101");
        assertRefused(printer, "drawer_pulse_ms = 512");
        assertRefused(printer, "last_status = 'OK'");
    }

    @Test
    void thePresetsAreListedForAnyoneManagingPrinters() throws Exception {
        mvc.perform(get("/api/v1/backoffice/printer-profiles").with(inStore(store, Permission.PRODUCTION_MANAGE)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].name").value("EPSON_TM_80"))
                .andExpect(jsonPath("$[0].codePage").value("CP850"))
                .andExpect(jsonPath("$[0].profile.statusProbe").value(true))
                .andExpect(jsonPath("$[1].name").value("GENERIC_80_42"))
                .andExpect(jsonPath("$[1].profile.columns").value(42))
                .andExpect(jsonPath("$[2].name").value("GENERIC_58"))
                .andExpect(jsonPath("$[2].profile.paperWidthMm").value(58))
                .andExpect(jsonPath("$[2].profile.cutMode").value("NONE"));
        mvc.perform(get("/api/v1/backoffice/printer-profiles").with(inStore(store, Permission.PRODUCTION_VIEW)))
                .andExpect(status().isForbidden());
    }

    private void assertRefused(UUID printer, String assignment) {
        assertThatThrownBy(() -> jdbc.sql("UPDATE production.printer SET " + assignment + " WHERE id = ?")
                .param(printer).update()).as(assignment).isInstanceOf(DataIntegrityViolationException.class);
    }

    private ResultActions create(String profile) throws Exception {
        return mvc.perform(post(ProductionFixtures.PRINTERS).with(inStore(store, Permission.PRODUCTION_MANAGE))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""
                        {"storeId": "%s", "code": "P%s", "name": "Impressora", "host": "10.0.0.5", "port": 9100,
                         "codePage": "CP850", %s}""".formatted(store, UUID.randomUUID().toString().substring(0, 6),
                        profile)));
    }

    private ResultActions update(UUID printer, String profile) throws Exception {
        return mvc.perform(put(ProductionFixtures.PRINTERS + "/" + printer)
                .with(inStore(store, Permission.PRODUCTION_MANAGE)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"code": "KIT", "name": "Cozinha", "host": "10.0.0.9", "port": 9100, "codePage": "CP850",
                         "active": true, "version": %d%s}""".formatted(fixtures.printerVersion(printer),
                        profile.isEmpty() ? "" : ", " + profile)));
    }

    private static void expectInvalid(ResultActions result) throws Exception {
        result.andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PRINTER_PROFILE_INVALID"));
    }
}
