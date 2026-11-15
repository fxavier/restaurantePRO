package com.xavier.backend.production.controller;

import static com.xavier.backend.fiscal.FiscalActors.inStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.fiscal.FiscalFixtures;
import com.xavier.backend.production.ProductionFixtures;
import com.xavier.backend.production.ProductionModuleTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** BackOffice printers, modes, routes, terminal printers and the test page: status codes, codes and scope. */
@ProductionModuleTest
class PrinterHttpTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ProductionFixtures fixtures;

    @Autowired
    FiscalFixtures stores;

    UUID store;

    @BeforeEach
    void store() throws Exception {
        store = stores.newStore();
    }

    @Test
    void createListAndUpdateAPrinter() throws Exception {
        UUID printer = ProductionFixtures.id(fixtures.createPrinter(store, "KIT", "10.0.0.5", 9100)
                .andExpect(status().isCreated()).andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.mode").value("NORMAL")).andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.codePage").value("CP860")).andExpect(jsonPath("$.version").value(0)));

        mvc.perform(get(ProductionFixtures.PRINTERS).param("storeId", store.toString())
                        .with(inStore(store, Permission.PRODUCTION_MANAGE)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].code").value("KIT"));
        update(printer, 0, "COZ").andExpect(status().isOk()).andExpect(jsonPath("$.code").value("COZ"))
                .andExpect(jsonPath("$.active").value(false)).andExpect(jsonPath("$.version").value(1));
        update(printer, 0, "COZ2").andExpect(status().isConflict());
        assertThat(fixtures.audits("PRINTER_CREATED", printer)).isEqualTo(1);
        assertThat(fixtures.audits("PRINTER_UPDATED", printer)).isEqualTo(1);
    }

    @Test
    void aPrinterCodeIsUniqueInItsStore() throws Exception {
        fixtures.createPrinter(store, "KIT", "10.0.0.5", 9100).andExpect(status().isCreated());

        fixtures.createPrinter(store, "KIT", "10.0.0.6", 9100).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRINTER_CODE_TAKEN"));
        fixtures.createPrinter(stores.newStore(), "KIT", "10.0.0.6", 9100).andExpect(status().isCreated());
    }

    @Test
    void invalidInputIsRefused() throws Exception {
        fixtures.createPrinter(store, "KIT", "10.0.0.5", 70000).andExpect(status().isBadRequest());
        mvc.perform(post(ProductionFixtures.PRINTERS).with(inStore(store, Permission.PRODUCTION_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"storeId": "%s", "code": "K", "name": "K", "host": "h", "port": 9100,
                                 "codePage": "UTF8"}""".formatted(store)))
                .andExpect(status().isBadRequest());
        fixtures.createPrinter(UUID.randomUUID(), "KIT", "10.0.0.5", 9100).andExpect(status().isNotFound());
    }

    @Test
    void printersAreManagedOnlyWithThePermissionInTheirStore() throws Exception {
        UUID printer = fixtures.printer(store, "KIT", "10.0.0.5", 9100);

        mvc.perform(put(ProductionFixtures.PRINTERS + "/" + printer).with(inStore(stores.newStore(),
                        Permission.PRODUCTION_MANAGE)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("KIT", 0))).andExpect(status().isForbidden());
        mvc.perform(get(ProductionFixtures.PRINTERS).param("storeId", store.toString())
                .with(inStore(store, Permission.PRODUCTION_VIEW))).andExpect(status().isForbidden());
        // D42: an unknown printer is 404 before any permission is checked
        mvc.perform(put(ProductionFixtures.PRINTERS + "/" + UUID.randomUUID()).with(inStore(store,
                        Permission.PRODUCTION_VIEW)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("KIT", 0))).andExpect(status().isNotFound());
    }

    @Test
    void redirectNeedsAnotherPrinterOfTheStoreAndNoChains() throws Exception {
        UUID kitchen = fixtures.printer(store, "KIT", "10.0.0.5", 9100);
        UUID bar = fixtures.printer(store, "BAR", "10.0.0.6", 9100);
        UUID pass = fixtures.printer(store, "PAS", "10.0.0.7", 9100);
        UUID elsewhere = fixtures.printer(stores.newStore(), "X", "10.0.0.8", 9100);

        expectRedirectInvalid(fixtures.changeMode(store, kitchen, "REDIRECT", null, Permission.PRODUCTION_MANAGE));
        expectRedirectInvalid(fixtures.changeMode(store, kitchen, "REDIRECT", kitchen, Permission.PRODUCTION_MANAGE));
        expectRedirectInvalid(fixtures.changeMode(store, kitchen, "REDIRECT", elsewhere,
                Permission.PRODUCTION_MANAGE));
        fixtures.changeMode(store, kitchen, "REDIRECT", bar, Permission.PRODUCTION_MANAGE).andExpect(status().isOk());
        // bar is a redirect target: it cannot redirect too, and nothing can redirect to kitchen (a redirect)
        expectRedirectInvalid(fixtures.changeMode(store, bar, "REDIRECT", pass, Permission.PRODUCTION_MANAGE));
        expectRedirectInvalid(fixtures.changeMode(store, pass, "REDIRECT", kitchen, Permission.PRODUCTION_MANAGE));
        assertThat(fixtures.audits("PRINTER_MODE_CHANGED", kitchen)).isEqualTo(1);
    }

    @Test
    void aStaleModeVersionIsAConflict() throws Exception {
        UUID printer = fixtures.printer(store, "KIT", "10.0.0.5", 9100);

        mvc.perform(put(ProductionFixtures.PRINTERS + "/" + printer + "/mode").with(inStore(store,
                        Permission.PRODUCTION_MANAGE)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\": \"HOLD\", \"version\": 5}"))
                .andExpect(status().isConflict());
    }

    @Test
    void routesAndTerminalPrintersReplaceTheStoresTable() throws Exception {
        UUID kitchen = fixtures.printer(store, "KIT", "10.0.0.5", 9100);
        UUID bar = fixtures.printer(store, "BAR", "10.0.0.6", 9100);
        UUID terminal = UUID.randomUUID();

        fixtures.routes(store, Map.of("COZINHA", kitchen, "BAR", bar)).andExpect(status().isOk())
                .andExpect(jsonPath("$.routes[0].destinationCode").value("BAR"));
        fixtures.routes(store, Map.of("COZINHA", bar)).andExpect(status().isOk());
        mvc.perform(get(ProductionFixtures.ROUTES).param("storeId", store.toString())
                        .with(inStore(store, Permission.PRODUCTION_MANAGE))).andExpect(status().isOk())
                .andExpect(jsonPath("$.routes.length()").value(1))
                .andExpect(jsonPath("$.routes[0].printerId").value(bar.toString()));

        fixtures.terminalPrinters(store, Map.of(terminal, kitchen)).andExpect(status().isOk())
                .andExpect(jsonPath("$.assignments[0].terminalId").value(terminal.toString()));
        mvc.perform(get(ProductionFixtures.TERMINAL_PRINTERS).param("storeId", store.toString())
                        .with(inStore(store, Permission.PRODUCTION_MANAGE))).andExpect(status().isOk())
                .andExpect(jsonPath("$.assignments[0].printerId").value(kitchen.toString()));
    }

    /** Two replacements of one table at once: the loser gets 409, never 500. */
    @Test
    void aConcurrentRouteReplacementIsAConflict() throws Exception {
        UUID kitchen = fixtures.printer(store, "KIT", "10.0.0.5", 9100);
        java.util.concurrent.CountDownLatch inserted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        // another request of the store, on a thread of its own (no fixture fallback there)
        java.util.concurrent.CompletableFuture<Void> other = java.util.concurrent.CompletableFuture.runAsync(() ->
                scopes.callForStore(store, "test:other-request", () -> tx.execute(s -> {
                    jdbc.sql("INSERT INTO production.destination_route VALUES (?, 'BAR', ?)").param(store)
                            .param(kitchen).update();
                    inserted.countDown();
                    try {
                        release.await(20, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                })));
        assertThat(inserted.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        java.util.concurrent.CompletableFuture.delayedExecutor(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                .execute(release::countDown);

        fixtures.routes(store, Map.of("BAR", kitchen)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROUTES_CHANGED_CONCURRENTLY"));
        other.get(10, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Autowired
    org.springframework.transaction.support.TransactionTemplate tx;

    @Autowired
    com.xavier.backend.access.StoreScopes scopes;

    @Autowired
    org.springframework.jdbc.core.simple.JdbcClient jdbc;

    @Test
    void aRouteMustNameAPrinterOfTheStoreOnce() throws Exception {
        UUID elsewhere = fixtures.printer(stores.newStore(), "X", "10.0.0.8", 9100);
        UUID kitchen = fixtures.printer(store, "KIT", "10.0.0.5", 9100);

        fixtures.routes(store, Map.of("BAR", elsewhere)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PRINTER_NOT_IN_STORE"));
        mvc.perform(put(ProductionFixtures.ROUTES).with(inStore(store, Permission.PRODUCTION_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"storeId": "%s", "routes": [{"destinationCode": "BAR", "printerId": "%s"},
                                  {"destinationCode": "BAR", "printerId": "%s"}]}""".formatted(store, kitchen, kitchen)))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("DUPLICATE_ROUTE"));
    }

    @Test
    void aTerminalPrintsInOneStoreOnly() throws Exception {
        UUID terminal = UUID.randomUUID();
        fixtures.terminalPrinters(store, Map.of(terminal, fixtures.printer(store, "KIT", "10.0.0.5", 9100)))
                .andExpect(status().isOk());
        UUID other = stores.newStore();

        fixtures.terminalPrinters(other, Map.of(terminal, fixtures.printer(other, "KIT", "10.0.0.5", 9100)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TERMINAL_ASSIGNED_ELSEWHERE"));
    }

    @Test
    void aTestPageIsATestTicketAndAQueuedJobAndIsIdempotent() throws Exception {
        UUID printer = fixtures.printer(store, "KIT", "10.0.0.5", 9100);
        String key = UUID.randomUUID().toString();

        String first = test(printer, key).andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceType").value("TEST")).andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.printerId").value(printer.toString()))
                .andReturn().getResponse().getContentAsString();
        String again = test(printer, key).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString();

        assertThat(again).isEqualTo(first);
        assertThat(fixtures.audits("PRINTER_TEST_PRINTED", printer)).isEqualTo(1);
        mvc.perform(post(ProductionFixtures.PRINTERS + "/" + printer + "/test")
                        .header("Idempotency-Key", UUID.randomUUID().toString()).with(csrf())
                        .with(inStore(store, Permission.PRODUCTION_OPERATE)))
                .andExpect(status().isForbidden());
    }

    private ResultActions test(UUID printer, String key) throws Exception {
        return mvc.perform(post(ProductionFixtures.PRINTERS + "/" + printer + "/test").header("Idempotency-Key", key)
                .with(csrf()).with(inStore(store, Permission.PRODUCTION_MANAGE)));
    }

    private ResultActions update(UUID printer, long version, String code) throws Exception {
        return mvc.perform(put(ProductionFixtures.PRINTERS + "/" + printer)
                .with(inStore(store, Permission.PRODUCTION_MANAGE)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body(code, version).replace("true", "false")));
    }

    private static String body(String code, long version) {
        return """
                {"code": "%s", "name": "Cozinha", "host": "10.0.0.9", "port": 9100, "codePage": "CP850",
                 "active": true, "version": %d}""".formatted(code, version);
    }

    private static void expectRedirectInvalid(ResultActions result) throws Exception {
        result.andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PRINTER_REDIRECT_INVALID"));
    }
}
