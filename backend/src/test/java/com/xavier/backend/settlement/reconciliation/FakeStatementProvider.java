package com.xavier.backend.settlement.reconciliation;

import com.xavier.backend.settlement.ChargeRequest;
import com.xavier.backend.settlement.PaymentProvider;
import com.xavier.backend.settlement.ProviderOutcome;
import com.xavier.backend.shared.ExternalCalls;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * A provider adapter that also fetches statements (D78), under the test provider's code {@code FAKE}:
 * {@link #statement} answers the lines a test gave it, remembering whether a transaction was open when it was called.
 * {@link #registerIn} adds it to the cached settlement test context at runtime, after settlement indexed its charging
 * providers, so the {@code FakePaymentProvider} still takes every charge and no new test context is created (each
 * one counts against Spring's context cache, and an eviction closes a context the whole run shares).
 */
public class FakeStatementProvider implements PaymentProvider, StatementSource {

    public static final String CODE = com.xavier.backend.settlement.FakePaymentProvider.CODE;
    static final String BEAN = "reconciliationFakeStatementProvider";

    private volatile List<StatementLine> lines = List.of();
    private final List<Boolean> calledInTransaction = new CopyOnWriteArrayList<>();

    public void willReturn(List<StatementLine> statement) {
        lines = List.copyOf(statement);
    }

    /** One entry per statement call: whether a transaction was active on the calling thread. */
    public List<Boolean> calledInTransaction() {
        return calledInTransaction;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public ProviderOutcome charge(ChargeRequest request) {
        ExternalCalls.requireNoTransaction();
        return new ProviderOutcome.Succeeded(CODE + "-" + request.reference());
    }

    @Override
    public ProviderOutcome status(String reference) {
        ExternalCalls.requireNoTransaction();
        return new ProviderOutcome.Succeeded(CODE + "-" + reference);
    }

    @Override
    public List<StatementLine> statement(String methodCode, LocalDate from, LocalDate to) {
        calledInTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
        ExternalCalls.requireNoTransaction();
        return lines;
    }

    /** The fake of {@code context}, registered on first use. */
    public static FakeStatementProvider registerIn(ConfigurableApplicationContext context) {
        var beans = context.getBeanFactory();
        synchronized (FakeStatementProvider.class) {
            if (!beans.containsSingleton(BEAN)) {
                beans.registerSingleton(BEAN, new FakeStatementProvider());
            }
        }
        return (FakeStatementProvider) beans.getSingleton(BEAN);
    }
}
