package com.xavier.backend.organization.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.access.Permission;
import com.xavier.backend.access.WithActor;
import com.xavier.backend.organization.MutableClock;
import com.xavier.backend.organization.OrganizationModuleTest;
import com.xavier.backend.organization.model.dto.StoreCreateRequest;
import com.xavier.backend.organization.model.dto.StoreSummary;
import com.xavier.backend.organization.model.dto.StoreUpdateRequest;
import com.xavier.backend.organization.model.event.StoreDeactivated;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.test.AssertablePublishedEvents;

/** D45: identity ends a deactivated store's POS sessions; it learns about the deactivation from this event. */
@OrganizationModuleTest
@WithActor(permissions = Permission.STORE_MANAGE)
class StoreDeactivatedTest {

    @Autowired
    StoreService stores;

    @Autowired
    MutableClock clock;

    @Test
    void deactivatingAStorePublishesStoreDeactivatedOnce(AssertablePublishedEvents events) {
        StoreSummary store = newStore();

        StoreSummary inactive = update(store, false);
        update(inactive, false); // still inactive: not a new deactivation

        assertThat(events.ofType(StoreDeactivated.class)
                .matching(e -> e.storeId().equals(store.id()) && e.at().equals(clock.instant())))
                .hasSize(1);
    }

    @Test
    void editingOrReactivatingAStorePublishesNothing(AssertablePublishedEvents events) {
        StoreSummary store = newStore();

        StoreSummary renamed = update(store, true);
        stores.update(renamed.id(), new StoreUpdateRequest(renamed.code(), renamed.name(), "Africa/Maputo",
                renamed.businessDayCutoff(), true, renamed.version()));

        assertThat(events.ofType(StoreDeactivated.class).matching(e -> e.storeId().equals(store.id()))).isEmpty();
    }

    private StoreSummary newStore() {
        return stores.create(new StoreCreateRequest("D" + UUID.randomUUID().toString().substring(0, 8), "Store",
                "Africa/Maputo", null));
    }

    private StoreSummary update(StoreSummary store, boolean active) {
        return stores.update(store.id(), new StoreUpdateRequest(store.code(), store.name() + "!",
                store.timezone().getId(), store.businessDayCutoff(), active, store.version()));
    }
}
