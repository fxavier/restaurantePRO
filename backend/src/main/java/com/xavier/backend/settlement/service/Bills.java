package com.xavier.backend.settlement.service;

import com.xavier.backend.orders.OrderBillingView;
import com.xavier.backend.orders.OrderLifecycle;
import com.xavier.backend.orders.OrdersApi;
import com.xavier.backend.settlement.exception.SettlementRule;
import com.xavier.backend.settlement.exception.SettlementRuleException;
import com.xavier.backend.settlement.model.FinancialState;
import com.xavier.backend.settlement.repository.PaymentRepository;
import com.xavier.backend.shared.Money;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * An order's bill as settlement computes it (spec §8.3): the due from orders, the paid from the payments, and what
 * follows from them. Nothing of it is stored.
 */
@Component
class Bills {

    private final OrdersApi orders;
    private final PaymentRepository payments;

    Bills(OrdersApi orders, PaymentRepository payments) {
        this.orders = orders;
        this.payments = payments;
    }

    Bill of(UUID orderId) {
        OrderBillingView view = orders.billingView(orderId);
        Money paid = Money.of(payments.paidFor(orderId), view.due().currency());
        return new Bill(view, paid);
    }

    record Bill(OrderBillingView view, Money paid) {

        Money due() {
            return view.due();
        }

        Money balance() {
            return view.due().minus(paid);
        }

        FinancialState financialState() {
            return FinancialState.of(view.due(), paid);
        }

        UUID storeId() {
            return view.storeId();
        }

        /** @throws SettlementRuleException {@code ORDER_NOT_OPEN} for a CLOSED or CANCELLED order */
        Bill requireOpen() {
            if (view.lifecycle() != OrderLifecycle.OPEN) {
                throw new SettlementRuleException(SettlementRule.ORDER_NOT_OPEN,
                        "Order " + view.orderId() + " is " + view.lifecycle() + ".");
            }
            return this;
        }
    }
}
