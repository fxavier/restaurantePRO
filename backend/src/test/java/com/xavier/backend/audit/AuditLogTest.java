package com.xavier.backend.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.TestcontainersConfiguration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.IllegalTransactionStateException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.transaction.support.TransactionTemplate;

@ApplicationModuleTest(extraIncludes = "shared")
@Import(TestcontainersConfiguration.class)
class AuditLogTest {

    @Autowired
    AuditLog auditLog;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    JdbcClient jdbc;

    private final UUID actorId = UUID.randomUUID();
    private final UUID approverId = UUID.randomUUID();
    private final UUID storeId = UUID.randomUUID();
    private final UUID terminalId = UUID.randomUUID();
    private String entityId;

    @BeforeEach
    void newEntity() {
        // the database is shared by the whole test JVM: each test audits its own entity
        entityId = UUID.randomUUID().toString();
    }

    @Test
    void recordsWhoDidWhatWhereAndWhy() {
        transaction.executeWithoutResult(tx -> auditLog.record(entry("STORE_UPDATED", Map.of("field", "timezone"))));

        Map<String, Object> row = jdbc.sql("""
                        SELECT actor_id, approver_id, action, entity_type, store_id, terminal_id, reason,
                               details->>'field' AS field, occurred_at
                        FROM audit.audit_event WHERE entity_id = :entityId
                        """)
                .param("entityId", entityId).query().singleRow();

        assertThat(row)
                .containsEntry("actor_id", actorId)
                .containsEntry("approver_id", approverId)
                .containsEntry("action", "STORE_UPDATED")
                .containsEntry("entity_type", "Store")
                .containsEntry("store_id", storeId)
                .containsEntry("terminal_id", terminalId)
                .containsEntry("reason", "customer asked")
                .containsEntry("field", "timezone");
        assertThat(row.get("occurred_at")).isNotNull();
    }

    @Test
    void theDatabaseRejectsUpdatingOrDeletingAnAuditRow() {
        transaction.executeWithoutResult(tx -> auditLog.record(entry("STORE_CREATED", Map.of())));

        assertThatThrownBy(() -> jdbc.sql("UPDATE audit.audit_event SET reason = 'tampered' WHERE entity_id = :id")
                .param("id", entityId).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM audit.audit_event WHERE entity_id = :id")
                .param("id", entityId).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause().hasMessageContaining("append-only");
        assertThat(countRows()).isEqualTo(1);
    }

    @Test
    void theAuditRowRollsBackWithTheCallersTransaction() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(tx -> {
            auditLog.record(entry("STORE_UPDATED", Map.of()));
            throw new IllegalStateException("use case failed after auditing");
        })).hasMessageContaining("use case failed");

        assertThat(countRows()).isZero();
    }

    @Test
    void recordingOutsideATransactionIsRefused() {
        assertThatThrownBy(() -> auditLog.record(entry("STORE_UPDATED", Map.of())))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(countRows()).isZero();
    }

    @Test
    void detailsNeverCarrySecrets() {
        for (String secretKey : new String[] {"pin", "newPassword", "cardNumber", "deviceToken", "cvv"}) {
            assertThatIllegalArgumentException().isThrownBy(() -> transaction.executeWithoutResult(
                    tx -> auditLog.record(entry("EMPLOYEE_UPDATED", Map.of(secretKey, "x")))));
        }
        assertThat(countRows()).isZero();
    }

    @Test
    void missingDetailsAreStoredAsAnEmptyObject() {
        transaction.executeWithoutResult(tx -> auditLog.record(entry("STORE_CREATED", null)));

        String details = jdbc.sql("SELECT details::text FROM audit.audit_event WHERE entity_id = :id")
                .param("id", entityId).query(String.class).single();
        assertThat(details).isEqualTo("{}");
    }

    private long countRows() {
        return jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE entity_id = :id")
                .param("id", entityId).query(Long.class).single();
    }

    private AuditEntry entry(String action, Map<String, Object> details) {
        return new AuditEntry(actorId, approverId, action, "Store", entityId, storeId, terminalId,
                "customer asked", details);
    }
}
