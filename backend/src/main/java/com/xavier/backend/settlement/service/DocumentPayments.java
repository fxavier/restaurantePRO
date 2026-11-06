package com.xavier.backend.settlement.service;

import com.xavier.backend.fiscal.FiscalApi;
import com.xavier.backend.fiscal.model.dto.DocumentView;
import com.xavier.backend.fiscal.model.dto.PaymentSnapshot;
import com.xavier.backend.fiscal.model.dto.PaymentSnapshot.PaidAmount;
import com.xavier.backend.fiscal.model.dto.PaymentStatus;
import com.xavier.backend.settlement.model.BillPartition;
import com.xavier.backend.settlement.model.CreditCharge;
import com.xavier.backend.settlement.model.Payment;
import com.xavier.backend.settlement.model.PaymentMethod;
import com.xavier.backend.settlement.model.Refund;
import com.xavier.backend.settlement.model.TargetType;
import com.xavier.backend.settlement.repository.PaymentMethodRepository;
import com.xavier.backend.settlement.repository.PaymentRepository;
import com.xavier.backend.settlement.service.Bills.Bill;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * D97 rules 1–2: the payment snapshot of one of the order's documents, from its bill as committed. The document covers
 * its targets: a PARTITION document its partition; the residual ORDER document the ORDER target and every LIVE
 * partition without a document of its own (AMOUNT partitions, ITEMS partitions not invoiced apart). Per method, the
 * payments less the refunds on those targets (positive amounts only, in the order each method was first confirmed,
 * then by code), labelled with the method's name now; credit is Σ of their live charges; outstanding is the rest.
 */
@Component
public class DocumentPayments {

    private static final Logger log = LoggerFactory.getLogger(DocumentPayments.class);

    private final PaymentRepository payments;
    private final PaymentMethodRepository methods;
    private final FiscalApi fiscal;

    DocumentPayments(PaymentRepository payments, PaymentMethodRepository methods, FiscalApi fiscal) {
        this.payments = payments;
        this.methods = methods;
        this.fiscal = fiscal;
    }

    /**
     * Run under the order's settlement lock, so the bill does not move while it is read.
     *
     * @throws IllegalStateException when the covered targets hold more than the document's gross (impossible under
     *         D71/D74: a bug, never printed)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentSnapshot of(Bill bill, DocumentView document) {
        Targets covered = covered(bill, document);
        Money gross = document.totalGross();
        Money zero = Money.of(BigDecimal.ZERO, gross.currency());
        Map<String, Money> net = new LinkedHashMap<>();
        Map<String, Instant> firstConfirmed = new LinkedHashMap<>();
        for (Payment payment : payments.findByOrderIdOrderByConfirmedAtAscIdAsc(bill.view().orderId())) {
            if (payment.getTargetType() != TargetType.CREDIT_ACCOUNT && covered.has(payment.getPartitionId())) {
                net.merge(payment.getMethodCode(), payment.amount(), Money::plus);
                firstConfirmed.putIfAbsent(payment.getMethodCode(), payment.getConfirmedAt());
            }
        }
        for (Refund refund : bill.refunds()) {
            if (covered.has(refund.getPartitionId())) {
                net.merge(refund.getMethodCode(), zero.minus(refund.amount()), Money::plus);
            }
        }
        Map<String, String> labels = methods.findAllByOrderByCode().stream()
                .collect(Collectors.toMap(PaymentMethod::getCode, PaymentMethod::getName));
        List<PaidAmount> paid = net.entrySet().stream().filter(e -> e.getValue().amount().signum() > 0)
                .sorted(Comparator.comparing((Map.Entry<String, Money> e) -> firstConfirmed.get(e.getKey()))
                        .thenComparing(Map.Entry::getKey))
                .map(e -> new PaidAmount(e.getKey(), labels.getOrDefault(e.getKey(), e.getKey()), e.getValue()))
                .toList();
        Money paidTotal = paid.stream().map(PaidAmount::amount).reduce(zero, Money::plus);
        Money credit = bill.liveCharges().stream().filter(c -> covered.has(c.getPartitionId()))
                .map(CreditCharge::amount).reduce(zero, Money::plus);
        Money outstanding = gross.minus(paidTotal).minus(credit);
        if (outstanding.amount().signum() < 0) {
            String alarm = "Document " + document.id() + " of order " + bill.view().orderId() + ": its targets hold "
                    + paidTotal.amount() + " paid and " + credit.amount() + " on account, more than its gross "
                    + gross.amount();
            log.error("Payment snapshot refused (a bug, D71/D74): orderId={} documentId={}. {}",
                    bill.view().orderId(), document.id(), alarm);
            throw new IllegalStateException(alarm);
        }
        return new PaymentSnapshot(PaymentStatus.of(gross, paidTotal, credit), paid, credit, outstanding);
    }

    private Targets covered(Bill bill, DocumentView document) {
        if ("PARTITION".equals(document.sourceType())) {
            return new Targets(false, Set.of(document.sourceId()));
        }
        Set<UUID> ownDocument = fiscal.documentsOfOrder(bill.view().orderId()).stream()
                .filter(d -> "PARTITION".equals(d.sourceType())).map(DocumentView::sourceId)
                .collect(Collectors.toSet());
        Set<UUID> partitions = bill.live().stream().map(BillPartition::getId)
                .filter(id -> !ownDocument.contains(id)).collect(Collectors.toSet());
        return new Targets(true, partitions);
    }

    /** The targets a document covers: the ORDER ({@code partitionId == null}) and these partitions. */
    private record Targets(boolean order, Set<UUID> partitions) {

        boolean has(UUID partitionId) {
            return partitionId == null ? order : partitions.contains(partitionId);
        }
    }
}
