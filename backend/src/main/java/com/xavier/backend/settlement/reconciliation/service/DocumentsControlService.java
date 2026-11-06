package com.xavier.backend.settlement.reconciliation.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.customers.CustomersApi;
import com.xavier.backend.fiscal.FiscalApi;
import com.xavier.backend.fiscal.model.dto.Coverage;
import com.xavier.backend.fiscal.model.dto.DocumentView;
import com.xavier.backend.orders.OrderBillingView;
import com.xavier.backend.orders.OrdersApi;
import com.xavier.backend.settlement.model.dto.CreditChargeView;
import com.xavier.backend.settlement.reconciliation.exception.ReconciliationNotFoundException;
import com.xavier.backend.settlement.reconciliation.model.DocumentsControl;
import com.xavier.backend.settlement.reconciliation.model.OrderFacts;
import com.xavier.backend.settlement.reconciliation.model.dto.DocumentException;
import com.xavier.backend.settlement.reconciliation.model.dto.DocumentsControlView;
import com.xavier.backend.settlement.reconciliation.model.dto.ExplainedRoundingDifference;
import com.xavier.backend.settlement.reconciliation.repository.PaymentReads;
import com.xavier.backend.settlement.model.CreditCharge;
import com.xavier.backend.settlement.repository.CreditChargeRepository;
import com.xavier.backend.settlement.model.dto.TaxRoundingDifference;
import com.xavier.backend.settlement.service.OrderTaxDrift;
import com.xavier.backend.organization.OrganizationApi;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Documents against payments of a store's business day (D80), with {@code RECONCILIATION_VIEW}: the orders of that
 * day (their business day, P37) that have an ISSUED document, a payment, a credit charge, or are still OPEN, each
 * checked by {@link DocumentsControl} from the facts read now through the orders, fiscal and customers facades and
 * settlement's own payments, refunds and charges. Settlement owns it because it already sees all of them (D80).
 */
@Service
public class DocumentsControlService {

    private static final int PAGE = 200;

    private final AccessPolicy accessPolicy;
    private final OrganizationApi organization;
    private final OrdersApi orders;
    private final FiscalApi fiscal;
    private final CustomersApi customers;
    private final PaymentReads payments;
    private final CreditChargeRepository charges;
    private final OrderTaxDrift taxDrift;
    private final Clock clock;

    DocumentsControlService(AccessPolicy accessPolicy, OrganizationApi organization, OrdersApi orders,
                            FiscalApi fiscal, CustomersApi customers, PaymentReads payments,
                            CreditChargeRepository charges, OrderTaxDrift taxDrift, Clock clock) {
        this.accessPolicy = accessPolicy;
        this.organization = organization;
        this.orders = orders;
        this.fiscal = fiscal;
        this.customers = customers;
        this.payments = payments;
        this.charges = charges;
        this.taxDrift = taxDrift;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public DocumentsControlView documents(UUID storeId, LocalDate businessDate) {
        accessPolicy.require(Permission.RECONCILIATION_VIEW, storeId);
        if (!organization.storeExists(storeId)) {
            throw new ReconciliationNotFoundException("Store", storeId);
        }
        Instant now = clock.instant();
        List<DocumentException> exceptions = new ArrayList<>();
        List<ExplainedRoundingDifference> rounding = new ArrayList<>();
        for (UUID orderId : ordersOf(storeId, businessDate)) {
            OrderBillingView order = orders.billingView(orderId);
            if (!businessDate.equals(order.businessDay())) {
                continue;
            }
            DocumentsControl.Findings findings = DocumentsControl.check(facts(order), now);
            exceptions.addAll(findings.exceptions());
            rounding.addAll(findings.roundingDifferences());
        }
        return new DocumentsControlView(storeId, businessDate, List.copyOf(exceptions), List.copyOf(rounding));
    }

    /** Every order of the store the day may be about, in id order (each is then checked to be of that day). */
    private Set<UUID> ordersOf(UUID storeId, LocalDate businessDate) {
        Set<UUID> ids = new TreeSet<>();
        Page<UUID> page;
        int number = 0;
        do {
            page = fiscal.issuedDocumentIds(storeId, businessDate, businessDate, PageRequest.of(number++, PAGE));
            page.map(documentId -> fiscal.documentFacts(documentId).orderId()).filter(java.util.Objects::nonNull)
                    .forEach(ids::add);
        } while (page.hasNext());
        ids.addAll(payments.ordersPaidOn(storeId, businessDate));
        ids.addAll(payments.ordersChargedOn(storeId, businessDate));
        orders.openBillingViews(storeId).forEach(open -> ids.add(open.orderId()));
        return ids;
    }

    private OrderFacts facts(OrderBillingView order) {
        var currency = order.due().currency();
        List<OrderFacts.Line> lines = order.lines().stream().map(line -> new OrderFacts.Line(line.lineId(),
                line.netQty(), line.ratePercent(), line.tax())).toList();
        List<OrderFacts.Document> documents = fiscal.documentsOfOrder(order.orderId()).stream()
                .map(DocumentsControlService::document).toList();
        Map<UUID, BigDecimal> issued = fiscal.coverage(order.orderId()).issued().stream()
                .collect(Collectors.toMap(Coverage.CoveredLine::sourceLineId, Coverage.CoveredLine::quantity,
                        BigDecimal::add));
        List<OrderFacts.Charge> credit = charges.findByOrderIdOrderByCreatedAt(order.orderId()).stream()
                .map(CreditCharge::toView).map(this::charge).toList();
        return new OrderFacts(order.orderId(), order.lifecycle().name(), currency, lines, documents, issued,
                Money.of(payments.paidOf(order.orderId()).subtract(payments.refundedOf(order.orderId())), currency),
                credit, expectedDrift(order));
    }

    /** D68 amendment: per rate, the tax drift settlement's split leaves on a closed order (zero without one). */
    private Map<BigDecimal, Money> expectedDrift(OrderBillingView order) {
        if (order.lifecycle() != com.xavier.backend.orders.OrderLifecycle.CLOSED) {
            return Map.of();
        }
        return taxDrift.of(order.orderId()).stream().collect(Collectors.toMap(TaxRoundingDifference::ratePercent,
                TaxRoundingDifference::difference, Money::plus));
    }

    private static OrderFacts.Document document(DocumentView document) {
        Map<BigDecimal, Money> taxes = document.taxes().stream()
                .collect(Collectors.toMap(DocumentView.TaxSummary::rate, DocumentView.TaxSummary::tax, Money::plus));
        return new OrderFacts.Document(document.id(), document.status(), document.sourceType(), document.sourceId(),
                document.onAccount(), document.totalGross(), taxes, document.lastErrorAt());
    }

    /** A posted charge's debit is checked in the customer's ledger (D74: the sweeper may still have to post it). */
    private OrderFacts.Charge charge(CreditChargeView charge) {
        boolean debitPosted = charge.ledgerEntryId() != null && customers.openDebits(charge.customerId(),
                List.of(charge.ledgerEntryId())).containsKey(charge.ledgerEntryId());
        return new OrderFacts.Charge(charge.id(), charge.status(), charge.documentId(), charge.amount(),
                charge.createdAt(), debitPosted);
    }
}
