package com.xavier.backend.settlement.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.settlement.model.dto.OrderSettlementView;
import com.xavier.backend.settlement.service.Bills.Bill;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The POS reads of settlement: an order's bill and settlement state. */
@Service
public class SettlementQueryService {

    private final AccessPolicy accessPolicy;
    private final Bills bills;
    private final SettlementViews views;

    SettlementQueryService(AccessPolicy accessPolicy, Bills bills, SettlementViews views) {
        this.accessPolicy = accessPolicy;
        this.bills = bills;
        this.views = views;
    }

    @Transactional(readOnly = true)
    public OrderSettlementView settlement(UUID orderId) {
        PosContext pos = accessPolicy.requirePos(Permission.ORDER_VIEW);
        Bill bill = bills.of(orderId);
        accessPolicy.requireSessionStore(pos, bill.storeId(), PaymentAttemptService.ORDER, orderId);
        return views.of(bill);
    }
}
