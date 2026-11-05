package com.xavier.backend.settlement.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.customers.CustomerSnapshot;
import com.xavier.backend.customers.CustomersApi;
import com.xavier.backend.customers.LedgerEntryView;
import com.xavier.backend.customers.model.dto.AddressView;
import com.xavier.backend.fiscal.FiscalApi;
import com.xavier.backend.fiscal.model.dto.Buyer;
import com.xavier.backend.fiscal.model.dto.DocumentView;
import com.xavier.backend.fiscal.model.dto.DraftCommand;
import com.xavier.backend.orders.OrdersApi;
import com.xavier.backend.settlement.exception.SettlementConflictException;
import com.xavier.backend.settlement.exception.SettlementNotFoundException;
import com.xavier.backend.settlement.exception.SettlementRule;
import com.xavier.backend.settlement.exception.SettlementRuleException;
import com.xavier.backend.settlement.model.BillPartition;
import com.xavier.backend.settlement.model.CreditCharge;
import com.xavier.backend.settlement.model.CreditChargeStatus;
import com.xavier.backend.settlement.model.PartitionKind;
import com.xavier.backend.settlement.model.dto.CreditChargeRequest;
import com.xavier.backend.settlement.model.dto.CreditChargeView;
import com.xavier.backend.settlement.model.event.CreditChargePosted;
import com.xavier.backend.settlement.repository.CreditChargeRepository;
import com.xavier.backend.settlement.repository.PartitionRef;
import com.xavier.backend.settlement.service.Bills.Bill;
import com.xavier.backend.shared.Money;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The steps of a credit sale (D74, D63), each idempotent by domain state, so a retry with the same key after a
 * failure at any point resumes to one charge, one document number and one debit.
 *
 * <ul>
 *   <li><b>s1</b>, one transaction, in the D76 lock order: {@code order_settlement} → the target's balance and open
 *       attempts → {@code CustomersApi.reserve} ({@code credit_account}) → the RESERVED charge → the target's
 *       document draft, on account with the customer's snapshot as buyer (an ORDER target freezes the order for its
 *       residual document; a PARTITION target drafts its own). A live charge of the same customer and amount on the
 *       target is resumed as it is.</li>
 *   <li><b>s2</b>, no transaction: {@code FiscalApi.issue}.</li>
 *   <li><b>s3</b>, one transaction: {@code order_settlement} → RESERVED → POSTED with {@code CustomersApi.postDebit},
 *       publishing {@link CreditChargePosted}. The {@link CreditChargeSweeper} runs the same step for a charge whose
 *       sale stopped after s2.</li>
 * </ul>
 */
@Component
public class CreditSaleSteps {

    static final String CHARGE = SettlementAudit.CHARGE;

    private final AccessPolicy accessPolicy;
    private final SettlementLocks locks;
    private final Bills bills;
    private final OrdersApi orders;
    private final FiscalApi fiscal;
    private final CustomersApi customers;
    private final CreditChargeRepository charges;
    private final SettlementAudit audit;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate inTransaction;
    private final Clock clock;

    CreditSaleSteps(AccessPolicy accessPolicy, SettlementLocks locks, Bills bills, OrdersApi orders, FiscalApi fiscal,
                    CustomersApi customers, CreditChargeRepository charges, SettlementAudit audit,
                    ApplicationEventPublisher events, PlatformTransactionManager transactionManager, Clock clock) {
        this.accessPolicy = accessPolicy;
        this.locks = locks;
        this.bills = bills;
        this.orders = orders;
        this.fiscal = fiscal;
        this.customers = customers;
        this.charges = charges;
        this.audit = audit;
        this.events = events;
        this.inTransaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * s1. @return the RESERVED charge with its document draft, or the charge as it is when the target already has a
     *         live one of this customer and amount (a resumed sale)
     * @throws SettlementRuleException 422 {@code ORDER_NOT_OPEN}, {@code INVALID_AMOUNT},
     *         {@code AMOUNT_EXCEEDS_BALANCE}, {@code DOCUMENT_ALREADY_ISSUED}, {@code PARTITION_NOT_INVOICEABLE},
     *         {@code ORDER_FROZEN}, {@code NOTHING_TO_BILL}; customers' 422 {@code CREDIT_LIMIT_EXCEEDED},
     *         {@code CREDIT_BLOCKED}, {@code CREDIT_ACCOUNT_MISSING}, {@code CUSTOMER_INACTIVE}
     * @throws SettlementConflictException 409 {@code PAYMENT_IN_DOUBT}, {@code CREDIT_CHARGE_EXISTS}
     */
    public CreditChargeView reserve(PosContext pos, UUID orderId, CreditChargeRequest request, String requestKey) {
        return inTransaction.execute(tx -> {
            accessPolicy.requireSessionStore(pos, orders.storeOf(orderId), PaymentAttemptService.ORDER, orderId);
            locks.lockOrder(orderId);
            Bill bill = bills.of(orderId).requireOpen();
            Money amount = PaymentAttemptService.money(request.amount(), bill.due().currency());
            UUID partitionId = request.partitionId();
            Optional<CreditCharge> live = bill.liveChargeOn(partitionId);
            if (live.isPresent()) {
                return resumed(live.get(), request.customerId(), amount, requestKey, partitionId, orderId);
            }
            BillPartition partition = partitionId == null ? null : invoiceable(bill, partitionId);
            bill.openOn(partitionId).stream().findFirst().ifPresent(open -> {
                throw SettlementConflictException.inDoubt(targetName(partitionId),
                        partitionId == null ? orderId : partitionId, open.getId());
            });
            Money balance = partition == null ? bill.residualBalance() : bill.balanceOf(partition);
            if (amount.amount().compareTo(balance.amount()) > 0) {
                throw new SettlementRuleException(SettlementRule.AMOUNT_EXCEEDS_BALANCE, "The amount "
                        + amount.amount() + " is more than the balance " + balance.amount() + " of the "
                        + targetName(partitionId) + ".", Map.of("balance", balance.amount()));
            }
            List<DocumentView> documents = fiscal.documentsOfOrder(orderId);
            Optional<DocumentView> document = documentOf(documents, partitionId);
            if (document.isPresent() && "ISSUED".equals(document.get().status())) {
                throw SettlementRuleException.documentAlreadyIssued(document.get().id());
            }
            if (partition != null && document.isEmpty()) {
                bill.requireNotFrozen(); // the residual draft already holds the lines of an undrafted partition
            }
            CustomerSnapshot buyer = customers.snapshot(request.customerId());
            CreditCharge charge = CreditCharge.reserve(orderId, partitionId, bill.storeId(), pos.terminalId(),
                    request.customerId(), amount, bill.view().businessDay(), pos.employeeId(), requestKey,
                    clock.instant());
            customers.reserve(request.customerId(), amount, charge.getId());
            UniqueConstraints.saveAndFlush(charges, charge,
                    () -> SettlementConflictException.chargeExists(targetName(partitionId),
                            partitionId == null ? orderId : partitionId, charge.getId()),
                    "credit_charge_live_per_target_uk");
            DocumentView draft = fiscal.createDraft(draftOf(bill, partition, documents, buyer));
            charge.attachDocument(draft.id());
            charges.saveAndFlush(charge);
            audit.charge("CREDIT_CHARGE_RESERVED", charge.getId(), charge.getStoreId(), orderId,
                    charge.getTerminalId(), null, details(charge));
            return charge.toView();
        });
    }

    /** s2, no transaction (D55, D63): an ISSUED document comes back unchanged. */
    public DocumentView issue(PosContext pos, UUID documentId) {
        return fiscal.issue(documentId, pos.employeeId());
    }

    /**
     * s3, one transaction: the debit of the issued document, posted once.
     *
     * @throws SettlementRuleException 422 {@code FISCAL_PENDING} {documentId} while the document is not ISSUED
     * @throws SettlementConflictException 409 {@code ILLEGAL_CHARGE_STATE} for a RELEASED charge
     */
    public CreditChargeView post(PosContext pos, UUID chargeId) {
        return inTransaction.execute(tx -> {
            PartitionRef ref = charges.findRefById(chargeId)
                    .orElseThrow(() -> new SettlementNotFoundException(CHARGE, chargeId));
            locks.lockOrder(ref.orderId());
            return postLocked(charges.findById(chargeId).orElseThrow(), pos.employeeId()).toView();
        });
    }

    /**
     * Posts a charge whose order's settlement lock the caller holds: a POSTED charge is returned as it is. {@code
     * employeeId} is {@code null} for the sweeper (audited as the system).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    CreditCharge postLocked(CreditCharge charge, UUID employeeId) {
        if (charge.getStatus() == CreditChargeStatus.POSTED) {
            return charge;
        }
        if (charge.getStatus() == CreditChargeStatus.RESERVED && !documentIssued(charge)) {
            throw new SettlementRuleException(SettlementRule.FISCAL_PENDING, "The sale document of credit charge "
                    + charge.getId() + " is not issued yet.", Map.of("documentId", charge.getDocumentId()));
        }
        LedgerEntryView debit = customers.postDebit(charge.getId(), charge.getDocumentId(), charge.getStoreId(),
                charge.getBusinessDate(), charge.amount());
        charge.post(debit.id(), employeeId, clock.instant());
        charges.saveAndFlush(charge);
        audit.charge("CREDIT_CHARGE_POSTED", charge.getId(), charge.getStoreId(), charge.getOrderId(),
                charge.getTerminalId(), null, details(charge));
        events.publishEvent(new CreditChargePosted(charge.getId(), charge.getStoreId()));
        return charge;
    }

    /** Whether the charge's document is ISSUED (a charge has its draft from s1 on). */
    boolean documentIssued(CreditCharge charge) {
        return charge.getDocumentId() != null && fiscal.documentsOfOrder(charge.getOrderId()).stream()
                .anyMatch(d -> d.id().equals(charge.getDocumentId()) && "ISSUED".equals(d.status()));
    }

    private static CreditChargeView resumed(CreditCharge live, UUID customerId, Money amount, String requestKey,
                                            UUID partitionId, UUID orderId) {
        if (!live.resumableBy(customerId, amount, requestKey)) {
            throw SettlementConflictException.chargeExists(targetName(partitionId),
                    partitionId == null ? orderId : partitionId, live.getId());
        }
        return live.toView();
    }

    /** D70: only a LIVE ITEMS partition has a document of its own, so only it is sold on account by itself. */
    private static BillPartition invoiceable(Bill bill, UUID partitionId) {
        BillPartition partition = bill.partition(partitionId)
                .orElseThrow(() -> new SettlementNotFoundException(PartitionService.PARTITION, partitionId));
        if (partition.getKind() != PartitionKind.ITEMS || !partition.isLive()) {
            throw SettlementRuleException.partitionNotInvoiceable(partitionId, partition.getStatus(),
                    partition.getKind());
        }
        return partition;
    }

    private static Optional<DocumentView> documentOf(List<DocumentView> documents, UUID partitionId) {
        return documents.stream().filter(d -> partitionId == null ? "ORDER".equals(d.sourceType())
                : "PARTITION".equals(d.sourceType()) && partitionId.equals(d.sourceId())).findFirst();
    }

    /** The target's draft, on account: the residual of the order (frozen now), or the partition's own. */
    private DraftCommand draftOf(Bill bill, BillPartition partition, List<DocumentView> documents,
                                 CustomerSnapshot customer) {
        Buyer buyer = buyer(customer);
        if (partition != null) {
            return ResidualDocuments.onAccount(PartitionDocumentService.draftOf(bill, partition, buyer), buyer,
                    customer.customerId());
        }
        DraftCommand residual = ResidualDocuments.residual(bill, buyer, documents);
        if (!bill.frozen()) {
            if (residual.lines().isEmpty()) {
                throw new SettlementRuleException(SettlementRule.NOTHING_TO_BILL,
                        "Order " + bill.view().orderId() + " has nothing to bill: cancel it instead.");
            }
            orders.freezeForInvoice(bill.view().orderId());
        }
        return ResidualDocuments.onAccount(residual, buyer, customer.customerId());
    }

    /** §8.2: the document keeps the customer as read now (P17's inline buyer). */
    static Buyer buyer(CustomerSnapshot customer) {
        AddressView address = customer.billingAddress();
        String line = address == null ? null : Stream.of(address.line1(), address.line2(), address.postalCode(),
                        address.city()).filter(Objects::nonNull).filter(part -> !part.isBlank())
                .collect(Collectors.joining(", "));
        return new Buyer(customer.name(), customer.taxId(), line == null || line.isEmpty() ? null : line);
    }

    private static String targetName(UUID partitionId) {
        return partitionId == null ? "order" : "partition";
    }

    static Map<String, Object> details(CreditCharge charge) {
        Map<String, Object> details = new java.util.HashMap<>();
        details.put("orderId", charge.getOrderId().toString());
        details.put("customerId", charge.getCustomerId().toString());
        details.put("amount", charge.amount().amount().toPlainString());
        details.put("status", charge.getStatus().name());
        if (charge.getPartitionId() != null) {
            details.put("partitionId", charge.getPartitionId().toString());
        }
        if (charge.getDocumentId() != null) {
            details.put("documentId", charge.getDocumentId().toString());
        }
        return details;
    }
}
