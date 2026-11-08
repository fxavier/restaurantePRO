package com.xavier.backend.orders.delivery.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.orders.delivery.exception.DeliveryConflictException;
import com.xavier.backend.orders.delivery.model.DeliveryZone;
import com.xavier.backend.orders.delivery.model.dto.ZoneCreateRequest;
import com.xavier.backend.orders.delivery.model.dto.ZoneUpdateRequest;
import com.xavier.backend.orders.delivery.model.dto.ZoneView;
import com.xavier.backend.orders.delivery.repository.DeliveryZoneRepository;
import com.xavier.backend.orders.exception.OrderNotFoundException;
import com.xavier.backend.shared.persistence.OptimisticLocking;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A store's delivery zones (D128), managed in the BackOffice. Zones are referenced by deliveries' history, so they are
 * never deleted: {@code active = false} retires one. Whether the fee item is a published FEE is checked when a
 * delivery opens (422 {@code FEE_ITEM_NOT_PUBLISHED}), since the catalog may be published after the zone is set up.
 */
@Service
public class DeliveryZoneService {

    static final String CODE_UK = "delivery_zone_store_code_uk";

    private final DeliveryZoneRepository zones;
    private final AccessPolicy accessPolicy;
    private final AuditLog auditLog;

    DeliveryZoneService(DeliveryZoneRepository zones, AccessPolicy accessPolicy, AuditLog auditLog) {
        this.zones = zones;
        this.accessPolicy = accessPolicy;
        this.auditLog = auditLog;
    }

    /** @throws DeliveryConflictException 409 {@code DELIVERY_ZONE_CODE_TAKEN} */
    @Transactional
    public ZoneView create(ZoneCreateRequest request) {
        accessPolicy.require(Permission.DELIVERY_ZONE_MANAGE, request.storeId());
        DeliveryZone zone = UniqueConstraints.saveAndFlush(zones, DeliveryZone.create(request.storeId(),
                        request.code(), request.name(), request.feeItemId(), request.feeVariantId()),
                () -> DeliveryConflictException.zoneCodeTaken(request.code().strip()), CODE_UK);
        audit("DELIVERY_ZONE_CREATED", zone);
        return zone.toView();
    }

    @Transactional
    public ZoneView update(UUID zoneId, ZoneUpdateRequest request) {
        requireManaged(zoneId);
        DeliveryZone zone = zones.findById(zoneId)
                .orElseThrow(() -> new OrderNotFoundException(DeliveryZones.ENTITY, zoneId));
        OptimisticLocking.requireVersion(DeliveryZone.class, zoneId, zone.getVersion(), request.version());
        zone.change(request.name(), request.feeItemId(), request.feeVariantId(), request.active());
        zone = zones.saveAndFlush(zone);
        audit("DELIVERY_ZONE_CHANGED", zone);
        return zone.toView();
    }

    @Transactional(readOnly = true)
    public ZoneView get(UUID zoneId) {
        requireManaged(zoneId);
        return zones.findById(zoneId).map(DeliveryZone::toView)
                .orElseThrow(() -> new OrderNotFoundException(DeliveryZones.ENTITY, zoneId));
    }

    @Transactional(readOnly = true)
    public List<ZoneView> list(UUID storeId) {
        accessPolicy.require(Permission.DELIVERY_ZONE_MANAGE, storeId);
        return zones.findByStoreIdOrderByCode(storeId).stream().map(DeliveryZone::toView).toList();
    }

    /** The POS's choice of zones: the session store's active ones. */
    @Transactional(readOnly = true)
    public List<ZoneView> active() {
        PosContext pos = accessPolicy.requirePos(Permission.DELIVERY_OPERATE);
        return zones.findByStoreIdAndActiveTrueOrderByCode(pos.storeId()).stream().map(DeliveryZone::toView)
                .toList();
    }

    /** D42 (b): the zone's store only (404), then the permission there (403). */
    private void requireManaged(UUID zoneId) {
        UUID storeId = zones.findStoreIdById(zoneId)
                .orElseThrow(() -> new OrderNotFoundException(DeliveryZones.ENTITY, zoneId));
        accessPolicy.require(Permission.DELIVERY_ZONE_MANAGE, storeId);
    }

    private void audit(String action, DeliveryZone zone) {
        Map<String, Object> details = new HashMap<>();
        details.put("code", zone.getCode());
        details.put("feeItemId", zone.getFeeItemId().toString());
        details.put("active", zone.isActive());
        auditLog.recordAsCurrentActor(action, DeliveryZones.ENTITY, zone.getId().toString(), zone.getStoreId(), null,
                null, details);
    }
}
