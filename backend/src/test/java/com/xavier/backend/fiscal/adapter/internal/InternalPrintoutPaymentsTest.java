package com.xavier.backend.fiscal.adapter.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.fiscal.IssuedDocument;
import com.xavier.backend.fiscal.model.dto.PaymentSnapshot;
import com.xavier.backend.fiscal.model.dto.PaymentSnapshot.PaidAmount;
import com.xavier.backend.fiscal.model.dto.PaymentStatus;
import com.xavier.backend.shared.Money;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * D97 rule 6: the payment block the internal adapter prints after the TOTAL line, row by row of the table. Only an
 * ON_ACCOUNT document (credit = total) carries the whole-document "A CRÉDITO" header.
 */
class InternalPrintoutPaymentsTest {

    static final Currency MZN = Currency.getInstance("MZN");
    static final String TOTAL = "TOTAL 50.00 MZN";
    static final String ON_ACCOUNT = "A CRÉDITO";

    final InternalFiscalAdapter adapter = new InternalFiscalAdapter();

    @Test
    void paidWithOneMethod() {
        List<String> lines = print(snapshot(PaymentStatus.PAID, "0.00", "0.00", cash("50.00")), false);

        assertThat(after(lines)).containsExactly("Pagamento — Numerário: 50.00");
        assertThat(lines).doesNotContain(ON_ACCOUNT);
    }

    @Test
    void paidWithSeveralMethodsInSnapshotOrder() {
        List<String> lines = print(snapshot(PaymentStatus.PAID, "0.00", "0.00", cash("20.00"), card("30.00")), false);

        assertThat(after(lines)).containsExactly("Pagamento misto — Numerário: 20.00; Cartão: 30.00");
    }

    @Test
    void partiallyPaidShowsWhatIsStillOutstanding() {
        List<String> lines = print(snapshot(PaymentStatus.PARTIALLY_PAID, "0.00", "30.00", cash("20.00")), false);

        assertThat(after(lines)).containsExactly("Pagamento parcial — Numerário: 20.00", "Saldo em aberto: 30.00");
    }

    @Test
    void unpaidShowsOnlyTheOutstandingBalance() {
        List<String> lines = print(snapshot(PaymentStatus.UNPAID, "0.00", "50.00"), false);

        assertThat(after(lines)).containsExactly("Saldo em aberto: 50.00");
    }

    @Test
    void onAccountKeepsTheHeaderAndShowsTheCredit() {
        List<String> lines = print(snapshot(PaymentStatus.ON_ACCOUNT, "50.00", "0.00"), false);

        assertThat(lines).contains(ON_ACCOUNT);
        assertThat(after(lines)).containsExactly("Pagamento — A crédito: 50.00");
    }

    @Test
    void mixedShowsCashAndCreditWithoutTheHeader() {
        List<String> lines = print(snapshot(PaymentStatus.MIXED, "30.00", "0.00", cash("20.00")), false);

        assertThat(after(lines)).containsExactly("Pagamento misto — Numerário: 20.00; A crédito: 30.00");
        assertThat(lines).doesNotContain(ON_ACCOUNT);
    }

    @Test
    void mixedWithNoPaymentYetShowsTheCreditAndTheOutstandingBalance() {
        List<String> lines = print(snapshot(PaymentStatus.MIXED, "30.00", "20.00"), false);

        assertThat(after(lines)).containsExactly("Pagamento misto — A crédito: 30.00", "Saldo em aberto: 20.00");
        assertThat(lines).doesNotContain(ON_ACCOUNT);
    }

    @Test
    void mixedWithSeveralMethodsPutsTheCreditLastAndTheOutstandingAfter() {
        List<String> lines = print(snapshot(PaymentStatus.MIXED, "20.00", "5.00", card("15.00"), cash("10.00")),
                false);

        assertThat(after(lines)).containsExactly("Pagamento misto — Cartão: 15.00; Numerário: 10.00; A crédito: 20.00",
                "Saldo em aberto: 5.00");
    }

    @Test
    void aCopyPrintsTheSameBlock() {
        PaymentSnapshot mixed = snapshot(PaymentStatus.MIXED, "30.00", "20.00");

        List<String> copy = print(mixed, true);

        assertThat(copy).contains("CÓPIA");
        assertThat(after(copy)).isEqualTo(after(print(mixed, false)));
    }

    @Test
    void aDocumentWithoutSnapshotPrintsAsBefore() {
        IssuedDocument r2 = InternalFiscalAdapterTest.document();

        List<String> lines = adapter.printout(r2, false).lines();

        assertThat(lines.getLast()).isEqualTo("TOTAL 116.00 MZN");
        assertThat(lines).doesNotContain(ON_ACCOUNT);
    }

    private List<String> print(PaymentSnapshot payments, boolean copy) {
        IssuedDocument d = InternalFiscalAdapterTest.document();
        IssuedDocument fifty = new IssuedDocument(d.id(), d.documentType(), d.seriesCode(), d.number(), d.issuedAt(),
                d.storeZone(), d.companyName(), d.companyTaxId(), d.companyCountry(), d.storeCode(), d.storeName(),
                d.buyer(), d.lines(), d.taxes(), m("50.00"), d.totalNet(), d.totalTax(), d.adapterData(),
                d.qrPayload(), payments.credit().amount().signum() > 0, payments);
        return adapter.printout(fifty, copy).lines();
    }

    private static List<String> after(List<String> lines) {
        return lines.subList(lines.indexOf(TOTAL) + 1, lines.size());
    }

    private static PaymentSnapshot snapshot(PaymentStatus status, String credit, String outstanding,
                                            PaidAmount... methods) {
        return new PaymentSnapshot(status, List.of(methods), m(credit), m(outstanding));
    }

    private static PaidAmount cash(String amount) {
        return new PaidAmount("CASH", "Numerário", m(amount));
    }

    private static PaidAmount card(String amount) {
        return new PaidAmount("CARD", "Cartão", m(amount));
    }

    private static Money m(String amount) {
        return Money.of(amount, MZN);
    }
}
