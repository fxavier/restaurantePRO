package com.xavier.backend.dining.controller;

import static com.xavier.backend.dining.DiningActors.inStore;
import static com.xavier.backend.dining.DiningActors.pos;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.dining.DiningFixtures;
import com.xavier.backend.dining.DiningModuleTest;
import com.xavier.backend.shared.exception.BusinessRuleException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** {@code POST /api/v1/pos/tables/{id}/lock|unlock}: TABLE_LOCK in the session's store, Idempotency-Key, audited. */
@DiningModuleTest
class PosTableLockHttpTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    DiningFixtures fixtures;

    final UUID manager = UUID.randomUUID();
    UUID store;
    UUID area;
    UUID table;

    @BeforeEach
    void aStoreWithATable() throws Exception {
        store = fixtures.newStore();
        area = fixtures.createArea(store, "SALA");
        table = fixtures.createTable(store, area, "M1");
    }

    @Test
    void aManagerLocksATableWithAReasonAndItIsAudited() throws Exception {
        mvc.perform(lock(table, key()).with(pos(store, manager, Permission.TABLE_LOCK)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(table.toString()))
                .andExpect(jsonPath("$.locked").value(true))
                .andExpect(jsonPath("$.status").value("FREE"));

        assertThat(fixtures.audits("TABLE_LOCKED", table.toString(), store)).isEqualTo(1);
    }

    @Test
    void theBackOfficeSeesTheLockThatChangedTheTablesVersion() throws Exception {
        mvc.perform(get(DiningFixtures.TABLES + "/" + table).with(inStore(store, Permission.DINING_MANAGE)))
                .andExpect(jsonPath("$.locked").value(false)).andExpect(jsonPath("$.version").value(0));

        mvc.perform(lock(table, key()).with(pos(store, manager, Permission.TABLE_LOCK))).andExpect(status().isOk());

        mvc.perform(get(DiningFixtures.TABLES + "/" + table).with(inStore(store, Permission.DINING_MANAGE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(true)).andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void lockingIsIdempotentPerKeyAndLockingALockedTableChangesNothing() throws Exception {
        String key = key();
        mvc.perform(lock(table, key).with(pos(store, manager, Permission.TABLE_LOCK))).andExpect(status().isOk());
        mvc.perform(lock(table, key).with(pos(store, manager, Permission.TABLE_LOCK)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.locked").value(true));
        mvc.perform(lock(table, key()).with(pos(store, manager, Permission.TABLE_LOCK)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.locked").value(true));

        assertThat(fixtures.audits("TABLE_LOCKED", table.toString(), store)).isEqualTo(1);
    }

    @Test
    void unlockingALockedTableIsAuditedAndUnlockingAgainChangesNothing() throws Exception {
        mvc.perform(lock(table, key()).with(pos(store, manager, Permission.TABLE_LOCK))).andExpect(status().isOk());

        mvc.perform(unlock(table, key()).with(pos(store, manager, Permission.TABLE_LOCK)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.locked").value(false));
        mvc.perform(unlock(table, key()).with(pos(store, manager, Permission.TABLE_LOCK)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.locked").value(false));

        assertThat(fixtures.audits("TABLE_UNLOCKED", table.toString(), store)).isEqualTo(1);
    }

    @Test
    void aLockedTableCannotBeOpenedUntilItIsUnlocked() throws Exception {
        mvc.perform(lock(table, key()).with(pos(store, manager, Permission.TABLE_LOCK))).andExpect(status().isOk());

        assertThatThrownBy(() -> fixtures.seat(store, table)).isInstanceOfSatisfying(BusinessRuleException.class,
                e -> assertThat(e.code()).isEqualTo("TABLE_LOCKED"));

        mvc.perform(unlock(table, key()).with(pos(store, manager, Permission.TABLE_LOCK))).andExpect(status().isOk());
        fixtures.seat(store, table);
    }

    @Test
    void lockingNeedsAnIdempotencyKeyAndAReason() throws Exception {
        mvc.perform(post(DiningFixtures.POS_TABLES + "/" + table + "/lock").with(csrf())
                        .with(pos(store, manager, Permission.TABLE_LOCK))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"x\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(DiningFixtures.POS_TABLES + "/" + table + "/lock").with(csrf()).header("Idempotency-Key", key())
                        .with(pos(store, manager, Permission.TABLE_LOCK))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \" \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void lockingNeedsTableLockInAPosSessionOfTheTablesStore() throws Exception {
        UUID otherStore = fixtures.newStore();

        mvc.perform(lock(table, key()).with(pos(store, manager, Permission.DINING_VIEW)))
                .andExpect(status().isForbidden());
        mvc.perform(lock(table, key()).with(inStore(store, Permission.TABLE_LOCK)))
                .andExpect(status().isForbidden());
        mvc.perform(lock(table, key()).with(pos(otherStore, manager, Permission.TABLE_LOCK)))
                .andExpect(status().isForbidden());
        mvc.perform(unlock(table, key()).with(pos(otherStore, manager, Permission.TABLE_LOCK)))
                .andExpect(status().isForbidden());
        assertThat(fixtures.audits("TABLE_LOCKED", table.toString(), store)).isZero();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void lockingATableOfAnotherStoreIsLoggedAsADenial(CapturedOutput output) throws Exception {
        UUID otherStore = fixtures.newStore();

        mvc.perform(lock(table, key()).with(pos(otherStore, manager, Permission.TABLE_LOCK)))
                .andExpect(status().isForbidden());

        assertThat(output.getOut()).contains("Access denied").contains(table.toString())
                .contains(manager.toString()).contains(otherStore.toString());
    }

    @Test
    void lockingAnUnknownTableIsNotFound() throws Exception {
        mvc.perform(lock(UUID.randomUUID(), key()).with(pos(store, manager, Permission.TABLE_LOCK)))
                .andExpect(status().isNotFound());
    }

    static MockHttpServletRequestBuilder lock(UUID table, String key) {
        return post(DiningFixtures.POS_TABLES + "/" + table + "/lock").with(csrf()).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"Consumo em disputa\"}");
    }

    static MockHttpServletRequestBuilder unlock(UUID table, String key) {
        return post(DiningFixtures.POS_TABLES + "/" + table + "/unlock").with(csrf()).header("Idempotency-Key", key);
    }

    static String key() {
        return UUID.randomUUID().toString();
    }
}
