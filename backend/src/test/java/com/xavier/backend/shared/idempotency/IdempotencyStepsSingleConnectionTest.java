package com.xavier.backend.shared.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.shared.idempotency.IdempotencyStepsTest.Closed;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D63: an {@link IdempotencyService#executeSteps} request holds at most one connection at a time. With one pooled
 * connection, a request that held a connection (a transaction or the record's lock) while its steps open their own
 * transactions would time out: the steps would wait for the connection it holds. The inline properties outrank the
 * pool-size system property set by {@link TestcontainersConfiguration}.
 */
@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.connection-timeout=1000"})
@Import(TestcontainersConfiguration.class)
class IdempotencyStepsSingleConnectionTest {

    @Autowired
    IdempotencyService idempotency;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DataSource dataSource;

    private final String scope = "settlement.order.close:" + UUID.randomUUID();
    private final String key = UUID.randomUUID().toString();
    private final String target = "POST /api/v1/pos/orders/" + UUID.randomUUID() + "/close";
    private final Map<String, Object> body = Map.of("orderVersion", 3);

    @Test
    void theClaimTheStepsTheirOwnTransactionsAndTheCompletionAllFitInOneConnection() throws Exception {
        assertThat(dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize()).isEqualTo(1);
        TransactionTemplate stepTransaction = new TransactionTemplate(transactionManager);
        stepTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        assertThatThrownBy(() -> idempotency.executeSteps(scope, key, target, body, Closed.class, () -> {
            stepTransaction.executeWithoutResult(tx -> jdbc.sql("SELECT 1").query(Integer.class).single());
            throw new IllegalStateException("provider timeout");
        })).hasMessage("provider timeout");
        ResponseEntity<Closed> retry = idempotency.executeSteps(scope, key, target, body, Closed.class, () -> {
            stepTransaction.executeWithoutResult(tx -> jdbc.sql("SELECT 1").query(Integer.class).single());
            jdbc.sql("SELECT 2").query(Integer.class).single();
            stepTransaction.executeWithoutResult(tx -> jdbc.sql("SELECT 3").query(Integer.class).single());
            return ResponseEntity.ok(new Closed("CLOSED", 4));
        });
        ResponseEntity<Closed> replay = idempotency.executeSteps(scope, key, target, body, Closed.class,
                () -> ResponseEntity.ok(new Closed("must not run", 0)));

        assertThat(retry.getBody()).isEqualTo(new Closed("CLOSED", 4));
        assertThat(replay.getBody()).isEqualTo(new Closed("CLOSED", 4));
    }
}
