package com.xavier.backend.settlement.exception;

import com.xavier.backend.shared.Money;
import com.xavier.backend.shared.exception.BusinessRuleException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/** A settlement business rule refused the request: 422 with the stable {@code code} property. */
public class SettlementRuleException extends BusinessRuleException {

    private final SettlementRule rule;

    public SettlementRuleException(SettlementRule rule, String detail) {
        super(rule.name(), detail);
        this.rule = rule;
    }

    public SettlementRuleException(SettlementRule rule, String detail, Map<String, ?> properties) {
        super(rule.name(), detail, properties);
        this.rule = rule;
    }

    public static SettlementRuleException noOpenSession() {
        return new SettlementRuleException(SettlementRule.NO_OPEN_CASH_SESSION,
                "You have no open cash session in this store: open one before collecting.");
    }

    // ---- R3 (D67-D71): partitions and the settled void ----

    public static SettlementRuleException quantityAlreadyPartitioned(UUID lineId, BigDecimal available) {
        return new SettlementRuleException(SettlementRule.QUANTITY_ALREADY_PARTITIONED, "Only " + plain(available)
                + " of line " + lineId + " is not in a partition yet.",
                Map.of("lineId", lineId, "available", available));
    }

    public static SettlementRuleException quantityExceedsNet(UUID lineId, BigDecimal net, BigDecimal quantity) {
        return new SettlementRuleException(SettlementRule.QUANTITY_EXCEEDS_NET, "Line " + lineId + " has "
                + plain(net) + " billable, less than " + plain(quantity) + ".", Map.of("lineId", lineId));
    }

    public static SettlementRuleException quantityPartitioned(UUID lineId, BigDecimal available) {
        return new SettlementRuleException(SettlementRule.QUANTITY_PARTITIONED, "Only " + plain(available)
                + " of line " + lineId + " is in no partition: cancel the partition before voiding more.",
                Map.of("lineId", lineId, "available", available));
    }

    public static SettlementRuleException splitRoundingLimit(UUID lineId, BigDecimal quantity, Money gross,
                                                             Money tax) {
        return new SettlementRuleException(SettlementRule.SPLIT_ROUNDING_LIMIT, "Line " + lineId
                + " cannot be split this way: a share of " + plain(quantity) + " would carry " + gross.amount()
                + " (tax " + tax.amount() + "), beyond the rounding a document allows. Cancel or split it "
                + "differently.", Map.of("lineId", lineId));
    }

    public static SettlementRuleException orderFrozen(UUID orderId) {
        return new SettlementRuleException(SettlementRule.ORDER_FROZEN, "Order " + orderId
                + " has its document draft: its split can no longer change.");
    }

    public static SettlementRuleException amountExceedsResidual(UUID orderId, Money due, Money residual) {
        return new SettlementRuleException(SettlementRule.AMOUNT_EXCEEDS_RESIDUAL, "The partition of "
                + due.amount() + " is more than the residual " + residual.amount() + " of order " + orderId + ".",
                Map.of("residual", residual.amount()));
    }

    public static SettlementRuleException nothingToSplit(UUID orderId, Money residual, int parts) {
        return new SettlementRuleException(SettlementRule.NOTHING_TO_SPLIT, "The residual " + residual.amount()
                + " of order " + orderId + " cannot be split in " + parts + " positive parts.");
    }

    public static SettlementRuleException partitionInUse(UUID partitionId) {
        return new SettlementRuleException(SettlementRule.PARTITION_IN_USE, "Bill partition " + partitionId
                + " has a payment, an open attempt or a document: it cannot be cancelled.");
    }

    public static SettlementRuleException partitionNotInvoiceable(UUID partitionId, Object status, Object kind) {
        return new SettlementRuleException(SettlementRule.PARTITION_NOT_INVOICEABLE, "Only a LIVE ITEMS partition"
                + " is invoiced on its own; " + partitionId + " is " + status + " " + kind + ".");
    }

    public static SettlementRuleException voidExceedsUnpaid(UUID orderId, Money residualAfter, Money owed,
                                                            Money unpaid) {
        return new SettlementRuleException(SettlementRule.VOID_EXCEEDS_UNPAID, "The void would leave "
                + residualAfter.amount() + " due on order " + orderId + ", less than the " + owed.amount()
                + " paid or being paid: refund first.", Map.of("unpaid", unpaid.amount()));
    }

    /** @param what why the quantity is invoiced, e.g. "Order … has its document" */
    public static SettlementRuleException correctionRequiresFiscalAdapter(String what) {
        return new SettlementRuleException(SettlementRule.CORRECTION_REQUIRES_FISCAL_ADAPTER, what
                + ": correcting invoiced quantities needs the market's fiscal adapter.");
    }

    public static SettlementRuleException voidSelfApproval() {
        return new SettlementRuleException(SettlementRule.SELF_APPROVAL,
                "The approver of a void cannot be the operator.");
    }

    public static SettlementRuleException documentAlreadyIssued(UUID documentId) {
        return new SettlementRuleException(SettlementRule.DOCUMENT_ALREADY_ISSUED, "The sale document " + documentId
                + " is issued without this customer on account: it can no longer be a credit sale.",
                Map.of("documentId", documentId));
    }

    public static SettlementRuleException chargePosted(UUID chargeId) {
        return new SettlementRuleException(SettlementRule.CHARGE_POSTED, "Credit charge " + chargeId
                + " is posted or its document is issued: it is on the customer's account.");
    }

    public static SettlementRuleException amountExceedsDebt(Money amount, Money debt) {
        return new SettlementRuleException(SettlementRule.AMOUNT_EXCEEDS_DEBT, "The amount " + amount.amount()
                + " is more than the open debt " + debt.amount() + ".", Map.of("debt", debt.amount()));
    }

    public static SettlementRuleException debitNotOpen(UUID debitEntryId) {
        return new SettlementRuleException(SettlementRule.DEBIT_NOT_OPEN, "The debit " + debitEntryId
                + " is not an open debit of this customer.", Map.of("debitEntryId", debitEntryId));
    }

    // ---- R3 (D81): payments in doubt ----

    public static SettlementRuleException reclassifyTooEarly(UUID attemptId, java.time.Duration minAge,
                                                             boolean answerRequired, int answeredCount) {
        return new SettlementRuleException(SettlementRule.RECLASSIFY_TOO_EARLY, "Payment attempt " + attemptId
                + " may be reclassified as FAILED only once it is older than " + minAge + (answerRequired
                ? " and the provider answered a status query about it since it became UNKNOWN." : "."),
                Map.of("answeredCount", answeredCount));
    }

    // ---- R3 (D77): refunds ----

    public static SettlementRuleException refundExceedsRefundable(UUID paymentId, Money amount, Money refundable) {
        return new SettlementRuleException(SettlementRule.REFUND_EXCEEDS_REFUNDABLE, "The refund " + amount.amount()
                + " is more than the " + refundable.amount() + " still refundable of payment " + paymentId + ".",
                Map.of("refundable", refundable.amount()));
    }

    public static SettlementRuleException externalReferenceRequired(String methodCode) {
        return new SettlementRuleException(SettlementRule.EXTERNAL_REFERENCE_REQUIRED, "A refund to " + methodCode
                + " is made on the terminal or the provider's portal first: give its reference.");
    }

    public static SettlementRuleException refundSelfApproval() {
        return new SettlementRuleException(SettlementRule.SELF_APPROVAL,
                "The approver of a refund cannot be the refunder.");
    }

    private static String plain(BigDecimal quantity) {
        return quantity.stripTrailingZeros().toPlainString();
    }

    public SettlementRule rule() {
        return rule;
    }
}
