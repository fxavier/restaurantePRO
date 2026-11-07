package com.xavier.backend.inventory.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.inventory.exception.InventoryConflictException;
import com.xavier.backend.inventory.exception.InventoryRuleException;
import com.xavier.backend.inventory.model.InventoryActivation;
import com.xavier.backend.inventory.model.dto.ActivationRequest;
import com.xavier.backend.inventory.model.dto.ActivationView;
import com.xavier.backend.inventory.repository.InventoryActivationRepository;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Go-live of inventory per store (D122): one activation per store, never changed. Consumption (S47) uses
 * {@link #activeFrom}: only lines confirmed at or after it are consumed.
 */
@Service
public class ActivationService {

    static final String ENTITY = "InventoryActivation";

    private final InventoryActivationRepository activations;
    private final InventoryScope scope;
    private final AccessPolicy accessPolicy;
    private final AuditLog auditLog;
    private final Clock clock;

    ActivationService(InventoryActivationRepository activations, InventoryScope scope, AccessPolicy accessPolicy,
                      AuditLog auditLog, Clock clock) {
        this.activations = activations;
        this.scope = scope;
        this.accessPolicy = accessPolicy;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    /**
     * @throws InventoryConflictException INVENTORY_ALREADY_ACTIVE (409), also when two activations race
     * @throws InventoryRuleException     ACTIVATION_IN_PAST (422) for an {@code activeFrom} before now
     */
    @Transactional
    public ActivationView activate(ActivationRequest request) {
        scope.requireStore(Permission.INVENTORY_MANAGE, request.storeId());
        if (activations.existsById(request.storeId())) {
            throw InventoryConflictException.alreadyActive(request.storeId());
        }
        Instant now = clock.instant();
        Instant from = request.activeFrom() == null ? now : request.activeFrom();
        if (from.isBefore(now)) {
            throw InventoryRuleException.activationInPast(now);
        }
        UUID accountId = accessPolicy.current().userId();
        InventoryActivation activation = UniqueConstraints.saveAndFlush(activations,
                InventoryActivation.of(request.storeId(), from, accountId, now),
                () -> InventoryConflictException.alreadyActive(request.storeId()), "inventory_activation_pk");
        auditLog.recordAsCurrentActor("INVENTORY_ACTIVATED", ENTITY, request.storeId().toString(),
                request.storeId(), null, null, Map.of("activeFrom", from.toString()));
        return view(activation);
    }

    /** When inventory went live in {@code storeId}; empty while it is not active (no authorization: internal). */
    @Transactional(readOnly = true)
    public Optional<Instant> activeFrom(UUID storeId) {
        return activations.findById(storeId).map(InventoryActivation::getActiveFrom);
    }

    private static ActivationView view(InventoryActivation activation) {
        return new ActivationView(activation.getStoreId(), activation.getActiveFrom(),
                activation.getActivatedByAccountId(), activation.getActivatedAt());
    }
}
