package com.xavier.backend.settlement.service;

import com.xavier.backend.organization.model.event.CompanyProvisioned;
import com.xavier.backend.settlement.model.PaymentMethod;
import com.xavier.backend.settlement.model.PaymentMethodKind;
import com.xavier.backend.settlement.repository.PaymentMethodRepository;
import java.time.Clock;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Onboarding (D181 amended): the seeded CASH method (V9001) belongs to the bootstrap tenant, so every new tenant gets
 * its own when its company is provisioned. Runs in the event's tenant (the advisor binds it). Idempotent: a redelivery
 * finds the method and adds none, and {@code UNIQUE (tenant_id, code)} backs it.
 */
@Component
class CashMethodSeeding {

    private final PaymentMethodRepository methods;
    private final Clock clock;

    CashMethodSeeding(PaymentMethodRepository methods, Clock clock) {
        this.methods = methods;
        this.clock = clock;
    }

    @ApplicationModuleListener
    void on(CompanyProvisioned event) {
        if (methods.findByCode(PaymentMethod.CASH).isEmpty()) {
            methods.save(PaymentMethod.create(PaymentMethod.CASH, "Numerário", PaymentMethodKind.CASH, null,
                    clock.instant()));
        }
    }
}
