package com.xavier.backend.settlement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;

import com.xavier.backend.fiscal.FiscalApi;
import com.xavier.backend.fiscal.model.dto.DocumentView;
import com.xavier.backend.orders.BillingLock;
import com.xavier.backend.orders.OrderBillingView;
import com.xavier.backend.orders.OrderLifecycle;
import com.xavier.backend.orders.OrderMode;
import com.xavier.backend.settlement.repository.PaymentMethodRepository;
import com.xavier.backend.settlement.repository.PaymentRepository;
import com.xavier.backend.settlement.service.Bills.Bill;
import com.xavier.backend.shared.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * A snapshot whose targets hold more than the document's gross is a bug (D71/D74 forbid it): it is refused, and the
 * alarm is logged at ERROR with the order and document ids so an operator can find it.
 */
@ExtendWith(OutputCaptureExtension.class)
class DocumentPaymentsAlarmTest {

    static final UUID ORDER = UUID.randomUUID();
    static final UUID DOCUMENT = UUID.randomUUID();
    static final UUID PARTITION = UUID.randomUUID();

    @Test
    void aNegativeOutstandingIsLoggedAtErrorWithTheOrderAndTheDocument(CapturedOutput output) {
        DocumentPayments snapshots = new DocumentPayments(mock(PaymentRepository.class),
                mock(PaymentMethodRepository.class), mock(FiscalApi.class));

        // nothing paid on a document whose gross is below zero: the outstanding goes negative
        assertThatIllegalStateException().isThrownBy(() -> snapshots.of(bill(), partitionDocument("-1.00")));

        assertThat(output.getOut()).containsPattern("ERROR.*" + ORDER).contains(DOCUMENT.toString());
    }

    static Bill bill() {
        Money zero = Money.of("0.00", java.util.Currency.getInstance("MZN"));
        OrderBillingView view = new OrderBillingView(ORDER, UUID.randomUUID(), 0, OrderLifecycle.OPEN,
                BillingLock.INVOICE, LocalDate.of(2026, 11, 6), List.of(), zero, OrderMode.COUNTER, null, null);
        return new Bill(view, List.of(), zero, Map.of(), List.of(), List.of(), List.of());
    }

    static DocumentView partitionDocument(String gross) {
        Money total = Money.of(gross, java.util.Currency.getInstance("MZN"));
        return new DocumentView(DOCUMENT, UUID.randomUUID(), "SALE", "DRAFT", "PARTITION", PARTITION, null, null, null,
                null, null, null, null, null, null, null, List.of(), List.of(), total, total, total, null, null, null,
                null, 0L, ORDER, null, null, false, null, null);
    }
}
