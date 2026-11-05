package com.xavier.backend.production.service;

import static com.xavier.backend.production.ProductionFixtures.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.fiscal.FiscalActors;
import com.xavier.backend.orders.OrdersFixtures.Shop;
import com.xavier.backend.production.ProductionFixtures;
import com.xavier.backend.production.ProductionFixtures.Round;
import com.xavier.backend.production.ProductionModuleTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Spec §8.6: "Ignorar requer autorização e marca supressão explícita; não elimina trabalho sem rasto. Falha e
 * supressão são situações distintas." An IGNORE printer gets suppressed tickets, no job, and an audit row.
 */
@ProductionModuleTest
class IgnoreSuppressionTest {

    @Autowired
    ProductionFixtures fixtures;

    @Autowired
    MockMvc mvc;

    final UUID employee = UUID.randomUUID();
    final UUID terminal = UUID.randomUUID();

    @Test
    void ignoringAPrinterNeedsTheSuppressPermission() throws Exception {
        Shop shop = fixtures.shop();
        UUID bar = fixtures.barPrinter(shop.store(), "127.0.0.1", 9);

        fixtures.changeMode(shop.store(), bar, "IGNORE", null, Permission.PRODUCTION_MANAGE)
                .andExpect(status().isForbidden());

        assertThat(fixtures.audits("PRINTER_MODE_CHANGED", bar)).isZero();
        fixtures.changeMode(shop.store(), bar, "IGNORE", null, Permission.PRODUCTION_MANAGE,
                Permission.PRODUCTION_SUPPRESS).andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("IGNORE"));
        assertThat(fixtures.audits("PRINTER_MODE_CHANGED", bar)).isEqualTo(1);
    }

    @Test
    void anIgnoredPrinterGetsASuppressedTicketNoJobAndAnAuditRow() throws Exception {
        Shop shop = fixtures.shop();
        UUID bar = fixtures.barPrinter(shop.store(), "127.0.0.1", 9);
        fixtures.mode(shop.store(), bar, "IGNORE");

        Round round = fixtures.confirmedRound(shop, employee, terminal, "1");

        await(() -> "DISPATCHED".equals(fixtures.batchStatus(round.batch())), "batch dispatched");
        Map<String, Object> ticket = fixtures.tickets(round.batch()).getFirst();
        assertThat(ticket).containsEntry("suppressed", true).containsEntry("printer_id", bar);
        assertThat(fixtures.jobsOfSource(round.batch())).isEmpty();
        assertThat(fixtures.audits("PRODUCTION_TICKET_SUPPRESSED", (UUID) ticket.get("id"))).isEqualTo(1);

        mvc.perform(post("/api/v1/pos/production-tickets/" + ticket.get("id") + "/reprints")
                        .header("Idempotency-Key", UUID.randomUUID().toString()).with(csrf())
                        .with(FiscalActors.pos(shop.store(), employee, terminal, Permission.PRODUCTION_OPERATE)))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("PRINTER_IGNORED"));
    }

    @Test
    void aRedirectToAnIgnoredPrinterSuppressesToo() throws Exception {
        Shop shop = fixtures.shop();
        UUID bar = fixtures.barPrinter(shop.store(), "127.0.0.1", 9);
        UUID silent = fixtures.printer(shop.store(), "SIL" + UUID.randomUUID().toString().substring(0, 5),
                "127.0.0.1", 9);
        fixtures.mode(shop.store(), silent, "IGNORE");
        fixtures.changeMode(shop.store(), bar, "REDIRECT", silent, Permission.PRODUCTION_MANAGE)
                .andExpect(status().isOk());

        Round round = fixtures.confirmedRound(shop, employee, terminal, "1");

        await(() -> "DISPATCHED".equals(fixtures.batchStatus(round.batch())), "batch dispatched");
        assertThat(fixtures.tickets(round.batch()).getFirst()).containsEntry("suppressed", true);
        assertThat(fixtures.jobsOfSource(round.batch())).isEmpty();
    }
}
