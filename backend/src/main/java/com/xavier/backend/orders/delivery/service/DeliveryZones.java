package com.xavier.backend.orders.delivery.service;

import com.xavier.backend.orders.delivery.exception.DeliveryRuleException;
import com.xavier.backend.orders.delivery.model.DeliveryZone;
import com.xavier.backend.orders.delivery.repository.DeliveryZoneRepository;
import com.xavier.backend.orders.exception.OrderNotFoundException;
import com.xavier.backend.shared.persistence.StoreProbe;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** The zone a delivery is sent to: of the session's store (422 {@code ZONE_OTHER_STORE}) and active. */
@Component
public class DeliveryZones {

    static final String ENTITY = "DeliveryZone";

    private final DeliveryZoneRepository zones;
    private final StoreProbe probe;

    DeliveryZones(DeliveryZoneRepository zones, StoreProbe probe) {
        this.zones = zones;
        this.probe = probe;
    }

    /**
     * @throws OrderNotFoundException 404 for an unknown zone
     * @throws DeliveryRuleException  422 {@code ZONE_OTHER_STORE}, {@code ZONE_INACTIVE}
     */
    public DeliveryZone usable(UUID zoneId, UUID storeId) {
        // the store first (D42): row-level security hides another store's zone, which is 422, not 404
        UUID zoneStore = probe.storeOf(DeliveryZoneRepository.TABLE, zoneId)
                .orElseThrow(() -> new OrderNotFoundException(ENTITY, zoneId));
        if (!zoneStore.equals(storeId)) {
            throw DeliveryRuleException.zoneOtherStore(zoneId);
        }
        DeliveryZone zone = zones.findById(zoneId).orElseThrow();
        if (!zone.isActive()) {
            throw DeliveryRuleException.zoneInactive(zone.getCode());
        }
        return zone;
    }
}
