package com.xavier.backend.settlement.service;

import com.xavier.backend.customers.CustomersApi;
import com.xavier.backend.organization.OrganizationApi;
import com.xavier.backend.settlement.model.Payment;
import com.xavier.backend.shared.Money;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The lines of a debt payment's non-fiscal slip (D75, P33): what was paid, by which method, and the debt left. It is
 * not a fiscal document (a regime that requires one gets it from its market adapter).
 */
@Component
class ReceiptSlips {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final CustomersApi customers;
    private final OrganizationApi organization;

    ReceiptSlips(CustomersApi customers, OrganizationApi organization) {
        this.customers = customers;
        this.organization = organization;
    }

    /** @param debtLeft the customer's debt after this payment */
    List<String> lines(Payment payment, Money debtLeft) {
        List<String> lines = new ArrayList<>();
        lines.add("PAGAMENTO DE CONTA CORRENTE");
        lines.add("Documento nao fiscal");
        // the customer as read now, active or not, in the payment's own transaction (the read never throws)
        customers.findCustomer(payment.getCreditAccountId()).ifPresent(customer -> {
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

    private static String plain(Money money) {
        return money.amount().setScale(money.currency().getDefaultFractionDigits(), java.math.RoundingMode.UNNECESSARY)
                .toPlainString() + " " + money.currency().getCurrencyCode();
    }
}
