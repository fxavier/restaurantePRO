package com.xavier.backend.settlement.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.fiscal.FiscalApi;
import com.xavier.backend.fiscal.model.dto.DocumentView;
import com.xavier.backend.settlement.model.dto.CloseOrderRequest;
import com.xavier.backend.settlement.model.dto.IssueDocumentRequest;
import com.xavier.backend.settlement.model.dto.OrderSettlementView;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Issuing an order's sale document and closing the order (D55), the two D63 multi-step commands of settlement. No
 * transaction around them: {@link OrderSettlementSteps} runs each step in its own (s2, the issue, in none).
 *
 * <ul>
 *   <li>{@code /documents}: s1 freeze + draft, s2 issue → the ISSUED document.</li>
 *   <li>{@code /close}: s1 and s2 when there is no ISSUED SALE document yet, then s3 the close checks and the close
 *       → the order's settlement view, CLOSED.</li>
 * </ul>
 */
@Service
public class OrderSettlementService {

    private final AccessPolicy accessPolicy;
    private final OrderSettlementSteps steps;
    private final FiscalApi fiscal;

    OrderSettlementService(AccessPolicy accessPolicy, OrderSettlementSteps steps, FiscalApi fiscal) {
        this.accessPolicy = accessPolicy;
        this.steps = steps;
        this.fiscal = fiscal;
    }

    /**
     * @throws com.xavier.backend.fiscal.FiscalIssueFailedException 422 {@code FISCAL_PENDING} with
     *         {@code documentId}: the draft stays pending and a retry issues it with no gap
     */
    public DocumentView issueDocument(UUID orderId, IssueDocumentRequest request) {
        PosContext pos = accessPolicy.requirePos(Permission.PAYMENT_COLLECT);
        DocumentView draft = steps.prepareDocument(pos, orderId, request.buyer(), null);
        return steps.issue(pos, draft.id());
    }

    /**
     * @throws com.xavier.backend.settlement.exception.SettlementRuleException 422 {@code NOT_SETTLED},
     *         {@code FISCAL_PENDING}, {@code ORDER_NOT_OPEN}, {@code NOTHING_TO_BILL}
     * @throws com.xavier.backend.settlement.exception.SettlementConflictException 409 {@code PAYMENT_IN_DOUBT}
     */
    public OrderSettlementView close(UUID orderId, CloseOrderRequest request) {
        PosContext pos = accessPolicy.requirePos(Permission.PAYMENT_COLLECT);
        if (issuedDocument(pos, orderId).isEmpty()) {
            DocumentView draft = steps.prepareDocument(pos, orderId, null, request.orderVersion());
            steps.issue(pos, draft.id());
        }
        return steps.close(pos, orderId);
    }

    private Optional<DocumentView> issuedDocument(PosContext pos, UUID orderId) {
        Optional<DocumentView> document = fiscal.saleDocumentFor(orderId);
        document.ifPresent(found -> accessPolicy.requireSessionStore(pos, found.storeId(),
                PaymentAttemptService.ORDER, orderId));
        return document.filter(found -> "ISSUED".equals(found.status()));
    }
}
