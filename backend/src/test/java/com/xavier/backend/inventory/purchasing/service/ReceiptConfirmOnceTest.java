package com.xavier.backend.inventory.purchasing.service;

import static com.xavier.backend.inventory.purchasing.PurchasingSteps.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.inventory.InventoryFixtures;
import com.xavier.backend.inventory.InventoryModuleTest;
import com.xavier.backend.inventory.purchasing.PurchasingSteps;
import com.xavier.backend.inventory.purchasing.exception.PurchasingConflictException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Only a confirmed receipt changes stock (§8.4), and it posts once (D125, D133 "A receipt confirmed twice"): a CAS
 * DRAFT → CONFIRMED under the Idempotency-Key, plus the ledger's source key.
 */
@InventoryModuleTest
class ReceiptConfirmOnceTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    InventoryFixtures fixtures;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    GoodsReceiptService receipts;

    @Autowired
    TransactionTemplate tx;

    PurchasingSteps steps;
    UUID store;
    UUID warehouse;
    UUID item;
    UUID supplier;

    @BeforeEach
    void aLiveStoreAndASupplier() throws Exception {
        steps = new PurchasingSteps(mvc, jdbc);
        store = fixtures.newActiveStore();
        warehouse = fixtures.newWarehouse(store, "ALLOW");
        item = fixtures.newStockItem("un");
        supplier = steps.newSupplier();
    }

    @Test
    void aDraftMovesNothingAndOnlyItsConfirmationPostsTheReceipt() throws Exception {
        UUID receipt = steps.receipt(store, warehouse, supplier, line(item, "10", "10"));
        assertThat(steps.movementCount(warehouse)).isZero();

        steps.command(store, "/receipts/" + receipt + "/confirm").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.warnings").isEmpty());

        assertThat(steps.movements(GoodsReceiptService.SOURCE_TYPE, receipt))
                .containsExactly("RECEIPT|10.000000|10.000000|100.0000");
        assertThat(steps.balance(warehouse, item)).isEqualTo("10.000000|100.0000|10.000000");
    }

    @Test
    void aConfirmReplayedWithItsKeyAnswersTheSameAndPostsOnce() throws Exception {
        UUID receipt = steps.receipt(store, warehouse, supplier, line(item, "10", "10"));
        String key = UUID.randomUUID().toString();

        String first = steps.command(store, "/receipts/" + receipt + "/confirm", key).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String replay = steps.command(store, "/receipts/" + receipt + "/confirm", key).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(replay).isEqualTo(first);
        assertThat(steps.movements(GoodsReceiptService.SOURCE_TYPE, receipt)).hasSize(1);
        assertThat(steps.balance(warehouse, item)).startsWith("10.000000|100.0000");
    }

    @Test
    void aSecondConfirmWithAnotherKeyIsAConflictAndPostsNothing() throws Exception {
        UUID receipt = steps.receipt(store, warehouse, supplier, line(item, "10", "10"));
        steps.command(store, "/receipts/" + receipt + "/confirm").andExpect(status().isOk());

        steps.command(store, "/receipts/" + receipt + "/confirm").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECEIPT_STATE"));
        steps.command(store, "/receipts/" + receipt + "/cancel").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECEIPT_STATE"));

        assertThat(steps.movements(GoodsReceiptService.SOURCE_TYPE, receipt)).hasSize(1);
    }

    @Test
    void aDraftIsEditedWithItsVersionAndConfirmedAsEdited() throws Exception {
        String created = steps.newReceipt(store, warehouse, supplier, line(item, "10", "10")).andReturn()
                .getResponse().getContentAsString();
        UUID receipt = InventoryFixtures.id(created);
        long version = InventoryFixtures.json(created).get("version").asLong();
        assertThat(version).as("the version a client sends back is the one its create answered")
                .isEqualTo(jdbc.sql("SELECT version FROM inventory.goods_receipt WHERE id = ?").param(receipt)
                        .query(Long.class).single());
        String edit = """
                {"supplierDocumentRef": "FT 2026/88", "lines": [%s], "version": %d}""";

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(InventoryFixtures.BO
                                + "/receipts/" + receipt).with(PurchasingSteps.buyer(store))
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .csrf()).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(edit.formatted(line(item, "8", "11"), version)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.supplierDocumentRef").value("FT 2026/88"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(InventoryFixtures.BO
                                + "/receipts/" + receipt).with(PurchasingSteps.buyer(store))
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .csrf()).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(edit.formatted(line(item, "1", "1"), version)))
                .andExpect(status().isConflict());
        steps.command(store, "/receipts/" + receipt + "/confirm").andExpect(status().isOk());

        assertThat(steps.movements(GoodsReceiptService.SOURCE_TYPE, receipt))
                .containsExactly("RECEIPT|8.000000|11.000000|88.0000");
    }

    @Test
    void aCancelledDraftIsNeverConfirmed() throws Exception {
        UUID receipt = steps.receipt(store, warehouse, supplier, line(item, "10", "10"));
        steps.command(store, "/receipts/" + receipt + "/cancel").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        steps.command(store, "/receipts/" + receipt + "/confirm").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECEIPT_STATE"));
        assertThat(steps.movementCount(warehouse)).isZero();
    }

    @Test
    void beforeGoLiveAReceiptIsNotConfirmed() throws Exception {
        UUID notLive = fixtures.newStore();
        UUID room = fixtures.newWarehouse(notLive, "ALLOW");
        UUID receipt = steps.receipt(notLive, room, supplier, line(item, "1", "1"));

        steps.command(notLive, "/receipts/" + receipt + "/confirm").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVENTORY_NOT_ACTIVE"));
        assertThat(steps.movementCount(room)).isZero();
    }

    @Test
    void theReceiptIsAuthorizedInItsStoreBeforeAnythingElse() throws Exception {
        UUID receipt = steps.receipt(store, warehouse, supplier, line(item, "1", "1"));

        mvc.perform(InventoryFixtures.idem(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post(InventoryFixtures.BO + "/receipts/" + receipt + "/confirm"))
                        .with(InventoryFixtures.account(UUID.randomUUID(), Permission.GOODS_RECEIVE)))
                .andExpect(status().isForbidden());
        mvc.perform(InventoryFixtures.idem(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post(InventoryFixtures.BO + "/receipts/" + receipt + "/confirm"))
                        .with(InventoryFixtures.account(store, Permission.INVENTORY_VIEW)))
                .andExpect(status().isForbidden());
        steps.command(store, "/receipts/" + UUID.randomUUID() + "/confirm").andExpect(status().isNotFound());
        assertThat(steps.movementCount(warehouse)).isZero();
    }

    /**
     * Two confirmations with different keys queue on the receipt row (the test holds its lock until both wait):
     * one confirms and posts, the other finds it CONFIRMED.
     */
    @Test
    void twoConcurrentConfirmationsPostOnce() throws Exception {
        UUID receipt = steps.receipt(store, warehouse, supplier, line(item, "10", "10"));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Future<String>> confirmations = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(3)) {
            Future<?> lock = pool.submit(() -> tx.executeWithoutResult(status -> {
                jdbc.sql("SELECT id FROM inventory.goods_receipt WHERE id = ? FOR UPDATE").param(receipt)
                        .query(UUID.class).single();
                locked.countDown();
                Locks.await(release);
            }));
            Locks.await(locked);
            for (int i = 0; i < 2; i++) {
                confirmations.add(pool.submit(() -> {
                    SecurityContextHolder.getContext().setAuthentication(InventoryFixtures.authenticationOf(
                            InventoryFixtures.accountActor(store, Permission.GOODS_RECEIVE)));
                    try {
                        return receipts.confirm(receipt).status();
                    } catch (PurchasingConflictException e) {
                        return e.code();
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                }));
            }
            try {
                Locks.waitUntil(() -> Locks.blockedOn(jdbc, "goods_receipt") == 2);
            } finally {
                release.countDown();
            }
            lock.get(60, TimeUnit.SECONDS);
            List<String> outcomes = new ArrayList<>();
            for (Future<String> confirmation : confirmations) {
                outcomes.add(confirmation.get(60, TimeUnit.SECONDS));
            }
            assertThat(outcomes).containsExactlyInAnyOrder("CONFIRMED", "RECEIPT_STATE");
        }
        assertThat(steps.movements(GoodsReceiptService.SOURCE_TYPE, receipt)).hasSize(1);
        assertThat(steps.balance(warehouse, item)).startsWith("10.000000|100.0000");
    }
}
