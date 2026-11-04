package com.xavier.backend.shared;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** D48: an adapter that calls an external system first proves no transaction or connection is held. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ExternalCallsTest {

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JdbcClient jdbc;

    @Test
    void outsideAnyTransactionTheCallMayGoAhead() {
        assertThatCode(ExternalCalls::requireNoTransaction).doesNotThrowAnyException();
    }

    @Test
    void insideATransactionItRefuses() {
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(tx -> ExternalCalls.requireNoTransaction()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transaction");
    }

    @Test
    void insideAReadOnlyTransactionItRefusesToo() {
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);

        assertThatThrownBy(() -> readOnly.executeWithoutResult(tx -> ExternalCalls.requireNoTransaction()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aConnectionBoundWithoutATransactionAlsoRefuses() {
        // PROPAGATION_SUPPORTS: no actual transaction, but the JDBC call binds its connection to the thread
        TransactionTemplate supports = new TransactionTemplate(transactionManager);
        supports.setPropagationBehavior(TransactionDefinition.PROPAGATION_SUPPORTS);

        assertThatThrownBy(() -> supports.executeWithoutResult(tx -> {
            jdbc.sql("SELECT 1").query(Integer.class).single();
            ExternalCalls.requireNoTransaction();
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("connection");
    }
}
