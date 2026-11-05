package com.xavier.backend.settlement.reconciliation;

import com.xavier.backend.settlement.ChargeRequest;
import com.xavier.backend.settlement.PaymentProvider;
import com.xavier.backend.settlement.ProviderOutcome;
import com.xavier.backend.shared.ExternalCalls;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * An integrated provider (code {@code STMT}) that also fetches statements (D78): every charge succeeds with
 * provider reference {@code STMT-<attempt id>}, and {@link #statement} answers the lines a test gave it, remembering
 * whether a transaction was open when it was called.
 */
public class FakeStatementProvider implements PaymentProvider, StatementSource {

    public static final String CODE = "STMT";

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

    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {

        @Bean
        FakeStatementProvider fakeStatementProvider() {
            return new FakeStatementProvider();
        }
    }
}
