package com.xavier.backend.settlement.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.settlement.exception.SettlementConflictException;
import com.xavier.backend.settlement.exception.SettlementNotFoundException;
import com.xavier.backend.settlement.exception.SettlementRule;
import com.xavier.backend.settlement.exception.SettlementRuleException;
import com.xavier.backend.settlement.model.PaymentMethod;
import com.xavier.backend.settlement.model.PaymentMethodKind;
import com.xavier.backend.settlement.model.dto.PaymentMethodCreateRequest;
import com.xavier.backend.settlement.model.dto.PaymentMethodUpdateRequest;
import com.xavier.backend.settlement.model.dto.PaymentMethodView;
import com.xavier.backend.settlement.repository.PaymentMethodRepository;
import com.xavier.backend.shared.persistence.OptimisticLocking;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Company-wide payment methods (D52), managed with the installation-wide {@code PAYMENT_METHOD_MANAGE}. */
@Service
public class PaymentMethodService {

    static final String ENTITY = "PaymentMethod";

    private final AccessPolicy accessPolicy;
    private final PaymentMethodRepository methods;
    private final PaymentProviders providers;
    private final SettlementAudit audit;
    private final Clock clock;

    PaymentMethodService(AccessPolicy accessPolicy, PaymentMethodRepository methods, PaymentProviders providers,
                         SettlementAudit audit, Clock clock) {
        this.accessPolicy = accessPolicy;
        this.methods = methods;
        this.providers = providers;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<PaymentMethodView> list() {
        accessPolicy.require(Permission.PAYMENT_METHOD_MANAGE);
        return methods.findAllByOrderByCode().stream().map(PaymentMethod::toView).toList();
    }

    /** @throws SettlementRuleException 422 {@code PROVIDER_NOT_AVAILABLE} when no provider bean has that code */
    @Transactional
    public PaymentMethodView create(PaymentMethodCreateRequest request) {
        accessPolicy.require(Permission.PAYMENT_METHOD_MANAGE);
        requireProviderBean(request.kind(), request.providerCode());
        PaymentMethod method = PaymentMethod.create(request.code(), request.name(), request.kind(),
                request.providerCode(), clock.instant());
        PaymentMethod saved = UniqueConstraints.saveAndFlush(methods, method,
                () -> SettlementConflictException.codeTaken(request.code()), "payment_method_code_uk");
        audit.record("PAYMENT_METHOD_CREATED", ENTITY, saved.getId(), null, details(saved));
        return saved.toView();
    }

    @Transactional
    public PaymentMethodView update(UUID id, PaymentMethodUpdateRequest request) {
        accessPolicy.require(Permission.PAYMENT_METHOD_MANAGE);
        PaymentMethod method = methods.findById(id).orElseThrow(() -> new SettlementNotFoundException(ENTITY, id));
        OptimisticLocking.requireVersion(PaymentMethod.class, id, method.getVersion(), request.version());
        requireProviderBean(method.getKind(), request.providerCode());
        method.update(request.name(), request.providerCode(), request.active(), clock.instant());
        PaymentMethod saved = methods.saveAndFlush(method);
        audit.record("PAYMENT_METHOD_UPDATED", ENTITY, id, null, details(saved));
        return saved.toView();
    }

    private void requireProviderBean(PaymentMethodKind kind, String providerCode) {
        if (kind == PaymentMethodKind.INTEGRATED && providerCode != null && !providers.exists(providerCode)) {
            throw new SettlementRuleException(SettlementRule.PROVIDER_NOT_AVAILABLE,
                    "No payment provider with the code " + providerCode + " is installed.");
        }
    }

    private static Map<String, Object> details(PaymentMethod method) {
        Map<String, Object> details = new HashMap<>();
        details.put("code", method.getCode());
        details.put("kind", method.getKind().name());
        details.put("active", method.isActive());
        if (method.getProviderCode() != null) {
            details.put("providerCode", method.getProviderCode());
        }
        return details;
    }
}
