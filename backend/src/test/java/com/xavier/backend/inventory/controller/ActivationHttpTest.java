package com.xavier.backend.inventory.controller;

import static com.xavier.backend.inventory.InventoryFixtures.BO;
import static com.xavier.backend.inventory.InventoryFixtures.account;
import static com.xavier.backend.inventory.InventoryFixtures.idem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.inventory.InventoryFixtures;
import com.xavier.backend.inventory.InventoryModuleTest;
import com.xavier.backend.inventory.service.ActivationService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Go-live per store (D122): once, by {@code INVENTORY_MANAGE} in the store; a second activation is a 409. */
@InventoryModuleTest
class ActivationHttpTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    com.xavier.backend.organization.MutableClock clock;

    Instant from;

    @org.junit.jupiter.api.BeforeEach
    void tomorrow() {
        from = clock.instant().plus(java.time.Duration.ofDays(1)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    }

    @Autowired
    InventoryFixtures fixtures;

    @Autowired
    ActivationService activations;

    @Test
    void aStoreGoesLiveOnce() throws Exception {
        UUID store = fixtures.newStore();
        assertThat(activations.activeFrom(store)).isEmpty();

        activate(store, account(store, Permission.INVENTORY_MANAGE)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.storeId").value(store.toString()))
                .andExpect(jsonPath("$.activeFrom").value(from.toString()))
                .andExpect(jsonPath("$.activatedByAccountId").value(InventoryFixtures.ACCOUNT_ID.toString()));
        activate(store, account(store, Permission.INVENTORY_MANAGE)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVENTORY_ALREADY_ACTIVE"));

        assertThat(activations.activeFrom(store)).contains(from);
    }

    /** Without a start it goes live now; a start a few seconds old is now; an older one is refused (S47 review). */
    @Test
    void aStoreGoesLiveNowOrLaterNeverInThePast() throws Exception {
        UUID late = fixtures.newStore();
        UUID backdated = fixtures.newStore();

        mvc.perform(idem(post(BO + "/activations")).with(account(backdated, Permission.INVENTORY_MANAGE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeId\": \"%s\", \"activeFrom\": \"%s\"}".formatted(backdated,
                                clock.instant().minusSeconds(120))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ACTIVATION_IN_PAST"));
        mvc.perform(idem(post(BO + "/activations")).with(account(late, Permission.INVENTORY_MANAGE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeId\": \"%s\", \"activeFrom\": \"%s\"}".formatted(late,
                                clock.instant().minusSeconds(5))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.activeFrom").value(clock.instant().toString()));
        mvc.perform(idem(post(BO + "/activations")).with(account(backdated, Permission.INVENTORY_MANAGE))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"storeId\": \"%s\"}".formatted(backdated)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.activeFrom").value(clock.instant().toString()));
    }

    @Test
    void activationNeedsInventoryManageInTheStore() throws Exception {
        UUID store = fixtures.newStore();
        activate(store, account(UUID.randomUUID(), Permission.INVENTORY_MANAGE)).andExpect(status().isForbidden());
        activate(store, account(store, Permission.INVENTORY_VIEW)).andExpect(status().isForbidden());
        activate(UUID.randomUUID(), account(null, Permission.INVENTORY_MANAGE)).andExpect(status().isNotFound());
    }

    private ResultActions activate(UUID store, RequestPostProcessor actor) throws Exception {
        return mvc.perform(idem(post(BO + "/activations")).with(actor).contentType(MediaType.APPLICATION_JSON)
                .content("{\"storeId\": \"%s\", \"activeFrom\": \"%s\"}".formatted(store, from)));
    }
}
