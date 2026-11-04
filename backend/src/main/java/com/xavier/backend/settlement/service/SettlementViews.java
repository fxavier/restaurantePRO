package com.xavier.backend.settlement.service;

import com.xavier.backend.fiscal.FiscalApi;
import com.xavier.backend.fiscal.model.dto.DocumentView;
import com.xavier.backend.settlement.model.AttemptStatus;
import com.xavier.backend.settlement.model.FiscalState;
import com.xavier.backend.settlement.model.PaymentAttempt;
import com.xavier.backend.settlement.model.dto.OrderSettlementView;
import com.xavier.backend.settlement.repository.PaymentAttemptRepository;
import com.xavier.backend.settlement.service.Bills.Bill;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Builds an {@link OrderSettlementView} from the bill, the order's sale document and its open attempts. */
@Component
class SettlementViews {

    private final Bills bills;
    private final FiscalApi fiscal;
    private final PaymentAttemptRepository attempts;

    SettlementViews(Bills bills, FiscalApi fiscal, PaymentAttemptRepository attempts) {
        this.bills = bills;
        this.fiscal = fiscal;
        this.attempts = attempts;
    }

    OrderSettlementView of(UUID orderId) {
        return of(bills.of(orderId));
    }

    OrderSettlementView of(Bill bill) {
        UUID orderId = bill.view().orderId();
        Optional<DocumentView> document = fiscal.saleDocumentFor(orderId);
        return new OrderSettlementView(orderId, bill.storeId(), bill.view().lifecycle().name(),
                bill.view().version(), bill.due(), bill.paid(), bill.balance(), bill.financialState().name(),
                document.map(SettlementViews::fiscalState).orElse(FiscalState.NONE).name(),
                document.map(DocumentView::id).orElse(null),
                attempts.findByOrderIdAndStatusInOrderByCreatedAt(orderId, AttemptStatus.OPEN).stream()
                        .map(PaymentAttempt::toView).toList());
    }

    static FiscalState fiscalState(DocumentView document) {
        return "ISSUED".equals(document.status()) ? FiscalState.ISSUED : FiscalState.DRAFT;
    }
}
