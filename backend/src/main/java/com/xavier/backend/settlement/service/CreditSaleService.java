package com.xavier.backend.settlement.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.customers.CustomersApi;
import com.xavier.backend.fiscal.FiscalApi;
import com.xavier.backend.settlement.exception.SettlementNotFoundException;
import com.xavier.backend.settlement.exception.SettlementRuleException;
import com.xavier.backend.settlement.model.CreditCharge;
import com.xavier.backend.settlement.model.CreditChargeStatus;
import com.xavier.backend.settlement.model.dto.CancelCreditChargeRequest;
import com.xavier.backend.settlement.model.dto.CreditChargeRequest;
import com.xavier.backend.settlement.model.dto.CreditChargeView;
import com.xavier.backend.settlement.repository.CreditChargeRepository;
import com.xavier.backend.settlement.repository.PartitionRef;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Credit sales on the POS (D74, P29: {@code CREDIT_SALE}, within the customer's available credit, no override).
 * {@link #charge} is a D63 multi-step command, run by {@link CreditSaleSteps}; {@link #cancel} is one transaction.
 */
@Service
public class CreditSaleService {

    /** The idempotency operation of a credit sale; its scope (per actor) is part of the charge's request key. */
    public static final String CHARGE_OPERATION = "settlement.credit.charge";

    private final AccessPolicy accessPolicy;
    private final CreditSaleSteps steps;
    private final SettlementLocks locks;
    private final CreditChargeRepository charges;
    private final CustomersApi customers;
    private final PaymentLocks paymentLocks;
    private final FiscalApi fiscal;
    private final SettlementAudit audit;
    private final Clock clock;

    CreditSaleService(AccessPolicy accessPolicy, CreditSaleSteps steps, SettlementLocks locks,
                      CreditChargeRepository charges, CustomersApi customers, FiscalApi fiscal,
                      PaymentLocks paymentLocks, SettlementAudit audit, Clock clock) {
        this.accessPolicy = accessPolicy;
        this.steps = steps;
        this.locks = locks;
        this.charges = charges;
        this.customers = customers;
        this.paymentLocks = paymentLocks;
        this.fiscal = fiscal;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * s1 reserve and draft, s2 issue, s3 post; call with no transaction. {@code idempotencyKey} is the sale's
     * Idempotency-Key, kept on the charge with its actor scope: another key, or the same key of another principal,
     * never resumes a POSTED charge (409 {@code CREDIT_CHARGE_EXISTS}).
     * @return the POSTED charge
     *
     * @throws com.xavier.backend.fiscal.FiscalIssueFailedException 422 {@code FISCAL_PENDING} {documentId}: the
     *         charge stays RESERVED with its limit held, and a retry with the same key resumes it
     */
    public CreditChargeView charge(UUID orderId, CreditChargeRequest request, String idempotencyKey) {
        PosContext pos = accessPolicy.requirePos(Permission.CREDIT_SALE);
        // scoped as the idempotency store keys it, so another principal's equal raw key is another sale
        String requestKey = accessPolicy.current().idempotencyScope(CHARGE_OPERATION) + ":" + idempotencyKey;
        CreditChargeView reserved = steps.reserve(pos, orderId, request, requestKey);
        if (CreditChargeStatus.POSTED.name().equals(reserved.status())) {
            return reserved;
        }
        steps.issue(pos, orderId, reserved.documentId());
        return steps.post(pos, reserved.id());
    }

    /**
     * RESERVED → RELEASED while its document is not ISSUED: the customer's reservation is released and the draft is
     * no longer on account ({@code FiscalApi.clearOnAccount}), so whatever pays the target later issues a paid sale.
     *
     * @throws SettlementRuleException 422 {@code CHARGE_POSTED} for a POSTED charge or an ISSUED document
     * @throws com.xavier.backend.settlement.exception.SettlementConflictException 409 {@code ILLEGAL_CHARGE_STATE}
     *         for a RELEASED charge
     */
    @Transactional
    public CreditChargeView cancel(UUID chargeId, CancelCreditChargeRequest request) {
        PosContext pos = accessPolicy.requirePos(Permission.CREDIT_SALE);
        PartitionRef ref = charges.findRefById(chargeId)
                .orElseThrow(() -> new SettlementNotFoundException(CreditSaleSteps.CHARGE, chargeId));
        accessPolicy.requireSessionStore(pos, ref.storeId(), CreditSaleSteps.CHARGE, chargeId);
        locks.lockOrder(ref.orderId());
        CreditCharge charge = charges.findById(chargeId).orElseThrow();
        if (charge.getStatus() == CreditChargeStatus.POSTED || steps.documentIssued(charge)) {
            throw SettlementRuleException.chargePosted(chargeId);
        }
        charge.release(request.reason(), pos.employeeId(), clock.instant());
        customers.release(chargeId);
        fiscal.clearOnAccount(charge.getDocumentId()); // a paid sale's document must not print A CRÉDITO
        charges.saveAndFlush(charge);
        audit.charge("CREDIT_CHARGE_RELEASED", chargeId, charge.getStoreId(), charge.getOrderId(),
                charge.getTerminalId(), request.reason(), CreditSaleSteps.details(charge));
        paymentLocks.releaseIfIdle(charge.getOrderId());
        return charge.toView();
    }
}
