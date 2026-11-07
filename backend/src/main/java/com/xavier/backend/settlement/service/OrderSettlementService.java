package com.xavier.backend.settlement.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.fiscal.model.dto.DocumentView;
import com.xavier.backend.orders.OrdersApi;
import com.xavier.backend.settlement.model.dto.CloseOrderRequest;
import com.xavier.backend.settlement.model.dto.IssueDocumentRequest;
import com.xavier.backend.settlement.model.dto.OrderSettlementView;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Issuing an order's sale document and closing the order (D55), the two D63 multi-step commands of settlement. No
 * transaction around them: {@link OrderSettlementSteps} runs each step in its own (s2, the issue, in none).
 *
 * <ul>
 *   <li>{@code /documents}: s1 freeze + the residual draft, s2 issue → the ISSUED document.</li>
 *   <li>{@code /close} (D70): s1 and s2 when the residual document is needed (none ISSUED yet, and some quantity is
 *       on no partition document), then s3 the close checks and the close → the order's settlement view, CLOSED.
 *       When partition documents cover everything, no residual document exists.</li>
 * </ul>
 */
@Service
public class OrderSettlementService {

    private final AccessPolicy accessPolicy;
    private final OrderSettlementSteps steps;
    private final OrdersApi orders;

    OrderSettlementService(AccessPolicy accessPolicy, OrderSettlementSteps steps,
                           OrdersApi orders) {
        this.accessPolicy = accessPolicy;
        this.steps = steps;
        this.orders = orders;
    }

    /**
     * @throws com.xavier.backend.fiscal.FiscalIssueFailedException 422 {@code FISCAL_PENDING} with
     *         {@code documentId}: the draft stays pending and a retry issues it with no gap
     * @throws com.xavier.backend.settlement.exception.SettlementRuleException 422 {@code DELIVERY_NOT_DELIVERED}
     *         for a DELIVERY order not yet DELIVERED (P48), before anything is drafted
     */
    public DocumentView issueDocument(UUID orderId, IssueDocumentRequest request) {
        PosContext pos = accessPolicy.requirePos(Permission.PAYMENT_COLLECT);
        DocumentView draft = steps.prepareDocument(pos, orderId, request.buyer(), null);
        return steps.issue(pos, orderId, draft.id());
    }

    /**
     * D42 (b) first: the order's store only (404, 403), before its sale document is looked up.
     *
     * @throws com.xavier.backend.settlement.exception.SettlementRuleException 422 {@code NOT_SETTLED},
     *         {@code FISCAL_PENDING}, {@code ORDER_NOT_OPEN}, {@code NOTHING_TO_BILL}, {@code DELIVERY_NOT_DELIVERED}
     * @throws com.xavier.backend.settlement.exception.SettlementConflictException 409 {@code PAYMENT_IN_DOUBT}
     */
    public OrderSettlementView close(UUID orderId, CloseOrderRequest request) {
        PosContext pos = accessPolicy.requirePos(Permission.PAYMENT_COLLECT);
        accessPolicy.requireSessionStore(pos, orders.storeOf(orderId), PaymentAttemptService.ORDER, orderId);
        if (steps.needsResidualDocument(pos, orderId)) {
            DocumentView draft = steps.prepareDocument(pos, orderId, null, request.orderVersion());
            steps.issue(pos, orderId, draft.id());
        }
        return steps.close(pos, orderId);
    }
}
