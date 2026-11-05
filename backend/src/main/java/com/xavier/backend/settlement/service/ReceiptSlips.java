package com.xavier.backend.settlement.service;

import com.xavier.backend.customers.CustomerSnapshot;
import com.xavier.backend.customers.CustomersApi;
import com.xavier.backend.organization.OrganizationApi;
import com.xavier.backend.settlement.model.Payment;
import com.xavier.backend.shared.Money;
import com.xavier.backend.shared.exception.BusinessRuleException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The lines of a debt payment's non-fiscal slip (D75, P33): what was paid, by which method, and the debt left. It is
 * not a fiscal document (a regime that requires one gets it from its market adapter).
 */
@Component
class ReceiptSlips {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final CustomersApi customers;
    private final OrganizationApi organization;
    private final TransactionTemplate ownReadOnly;

    ReceiptSlips(CustomersApi customers, OrganizationApi organization, PlatformTransactionManager transactionManager) {
        this.customers = customers;
        this.organization = organization;
        this.ownReadOnly = new TransactionTemplate(transactionManager);
        this.ownReadOnly.setReadOnly(true);
        this.ownReadOnly.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** @param debtLeft the customer's debt after this payment */
    List<String> lines(Payment payment, Money debtLeft) {
        List<String> lines = new ArrayList<>();
        lines.add("PAGAMENTO DE CONTA CORRENTE");
        lines.add("Documento nao fiscal");
        customer(payment).ifPresent(customer -> {
            lines.add("Cliente: " + customer.name());
            if (customer.taxId() != null) {
                lines.add("NUIT: " + customer.taxId());
            }
        });
        lines.add("Data: " + WHEN.format(payment.getConfirmedAt().atZone(organization.zoneOf(payment.getStoreId()))));
        lines.add("Metodo: " + payment.getMethodCode());
        lines.add("Valor pago: " + plain(payment.amount()));
        lines.add("Divida restante: " + plain(debtLeft));
        lines.add("Recibo: " + payment.getId());
        return lines;
    }

    /**
     * The customer's name as read now, in a transaction of its own: an inactive customer still pays a debt, and the
     * refused snapshot must not mark the payment's transaction for rollback. Its slip then prints no name.
     */
    private Optional<CustomerSnapshot> customer(Payment payment) {
        try {
            return Optional.ofNullable(ownReadOnly.execute(tx -> customers.snapshot(payment.getCreditAccountId())));
        } catch (BusinessRuleException inactive) {
            return Optional.empty();
        }
    }

    private static String plain(Money money) {
        return money.amount().setScale(money.currency().getDefaultFractionDigits(), java.math.RoundingMode.UNNECESSARY)
                .toPlainString() + " " + money.currency().getCurrencyCode();
    }
}
