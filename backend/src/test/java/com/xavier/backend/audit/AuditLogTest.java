package com.xavier.backend.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.access.Actor;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.WithActor;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.IllegalTransactionStateException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.transaction.support.TransactionTemplate;

// "config": the store-scope hook (ADR 0039); a module test drops the configuration of modules it does not include
@ApplicationModuleTest(extraIncludes = {"shared", "access", "config"})
@Import(TestcontainersConfiguration.class)
class AuditLogTest {

    @Autowired
    AuditLog auditLog;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    JdbcClient jdbc;

    static final String STORE = "0190a000-0000-7000-8000-00000000000a";
    static final String EMPLOYEE = "0190e000-0000-7000-8000-00000000000e";
    static final String TERMINAL = "0190f000-0000-7000-8000-00000000000f";

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

    /**
     * D18: the append-only trigger refuses a change by any role (shown as the owner, in a transaction that is rolled
     * back anyway); the pool, under row-level security (V1106: no update or delete policy), reaches no row at all.
     */
    @Test
    void theDatabaseRejectsUpdatingOrDeletingAnAuditRow() {
        transaction.executeWithoutResult(tx -> auditLog.record(entry("STORE_CREATED", Map.of())));

        for (String change : List.of("UPDATE audit.audit_event SET reason = 'tampered' WHERE entity_id = :id",
                "DELETE FROM audit.audit_event WHERE entity_id = :id")) {
            assertThatThrownBy(() -> OwnerDatabase.shared().rolledBack(owner -> owner.sql(change)
                    .param("id", entityId).update()))
                    .as(change).isInstanceOf(DataIntegrityViolationException.class)
                    .rootCause().hasMessageContaining("append-only");
            assertThat(jdbc.sql(change).param("id", entityId).update()).as("the pool: %s", change).isZero();
        }
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
    void aSecretKeyNestedInAMapOrAListIsRejected() {
        Map<String, Object> nestedInAMap = Map.of("employee", Map.of("code", "E1", "pin", "1234"));
        Map<String, Object> nestedInAList = Map.of("approvals", List.of(Map.of("approver", "E2"),
                Map.of("approver", "E3", "approverPin", "9999")));
        for (Map<String, Object> details : List.of(nestedInAMap, nestedInAList)) {
            assertThatIllegalArgumentException().isThrownBy(() -> transaction.executeWithoutResult(
                    tx -> auditLog.record(entry("EMPLOYEE_UPDATED", details))))
                    .withMessageMatching("(?i).*pin.*");
        }
        assertThat(countRows()).isZero();
    }

    @Test
    void nestedDetailsWithoutSecretsAreStored() {
        transaction.executeWithoutResult(tx -> auditLog.record(entry("ORDER_TRANSFERRED",
                Map.of("lines", List.of(Map.of("lineId", "l-1", "qty", 2)), "to", Map.of("tableCode", "M2")))));

        assertThat(countRows()).isOne();
    }

    @Test
    void anUpperOrMixedCaseSecretKeyIsRejectedAtTheTopLevel() {
        for (String secretKey : new String[] {"PIN", "Pin", "PASSWORD", "API_TOKEN", "CARD_NUMBER", "CVV"}) {
            assertThatIllegalArgumentException().isThrownBy(() -> transaction.executeWithoutResult(
                    tx -> auditLog.record(entry("EMPLOYEE_UPDATED", Map.of(secretKey, "x")))))
                    .withMessageContaining(secretKey);
        }
        assertThat(countRows()).isZero();
    }

    @Test
    void anUpperCaseSecretKeyIsRejectedInANestedMapAndAPinInAListOfMaps() {
        Map<String, Object> nestedSecret = Map.of("x", Map.of("SECRET", 1));
        Map<String, Object> pinInAList = Map.of("counts", List.of(Map.of("methodCode", "CASH"), Map.of("pin", "1")));
        for (Map<String, Object> details : List.of(nestedSecret, pinInAList)) {
            assertThatIllegalArgumentException().isThrownBy(() -> transaction.executeWithoutResult(
                    tx -> auditLog.record(entry("EMPLOYEE_UPDATED", details))));
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

    @Test
    @WithActor(permissions = Permission.DINING_VIEW, storeId = STORE, employeeId = EMPLOYEE, terminalId = TERMINAL)
    void recordAsCurrentActorTakesWhoAndWhichTerminalFromTheSession() {
        transaction.executeWithoutResult(tx -> auditLog.recordAsCurrentActor("ORDER_LINE_VOIDED", "Order", entityId,
                UUID.fromString(STORE), approverId, "wrong dish", Map.of("lineId", "l-1")));

        assertThat(row()).containsEntry("actor_id", UUID.fromString(EMPLOYEE))
                .containsEntry("actor_kind", "EMPLOYEE")
                .containsEntry("terminal_id", UUID.fromString(TERMINAL))
                .containsEntry("store_id", UUID.fromString(STORE))
                .containsEntry("approver_id", approverId)
                .containsEntry("reason", "wrong dish");
    }

    @Test
    @WithActor(permissions = Permission.STORE_MANAGE)
    void aBackOfficeActorIsRecordedAsAnAccountWithoutATerminal() {
        transaction.executeWithoutResult(tx -> auditLog.recordAsCurrentActor("STORE_UPDATED", "Store", entityId,
                storeId, null, null, Map.of()));

        assertThat(row()).containsEntry("actor_id", UUID.fromString(WithActor.DEFAULT_USER_ID))
                .containsEntry("actor_kind", "ACCOUNT")
                .containsEntry("terminal_id", null)
                .containsEntry("approver_id", null);
    }

    @Test
    void recordAsSystemHasNoActorAndTheSystemKind() {
        transaction.executeWithoutResult(tx -> auditLog.recordAsSystem("PRINT_JOB_FAILED", "PrintJob", entityId,
                storeId, terminalId, "printer offline", Map.of("attempts", 5)));

        assertThat(row()).containsEntry("actor_id", null)
                .containsEntry("actor_kind", "SYSTEM")
                .containsEntry("terminal_id", terminalId)
                .containsEntry("store_id", storeId);
    }

    @Test
    void recordAsNamesAnActorThatIsNotYetTheSessionsPrincipal() {
        Actor loggingIn = Actor.employee(actorId, terminalId, storeId, Set.of());
        transaction.executeWithoutResult(tx -> auditLog.recordAs(loggingIn, "EMPLOYEE_LOGGED_IN", "Employee", entityId,
                storeId, null, Map.of()));

        assertThat(row()).containsEntry("actor_id", actorId).containsEntry("actor_kind", "EMPLOYEE")
                .containsEntry("terminal_id", terminalId);
    }

    @Test
    void recordAsCurrentActorWithoutASessionIsRefusedAndWritesNothing() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(tx -> auditLog.recordAsCurrentActor("STORE_UPDATED",
                "Store", entityId, storeId, null, null, Map.of())))
                .isInstanceOf(org.springframework.security.authentication.AuthenticationCredentialsNotFoundException.class);
        assertThat(countRows()).isZero();
    }

    @Test
    void theDatabaseRefusesASystemRowThatNamesAnActor() {
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO audit.audit_event (id, occurred_at, actor_id, actor_kind, action, entity_type, entity_id)
                        VALUES (:id, now(), :actor, 'SYSTEM', 'X_DONE', 'X', :entity)""")
                .param("id", UUID.randomUUID()).param("actor", actorId).param("entity", entityId).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Map<String, Object> row() {
        return jdbc.sql("""
                        SELECT actor_id, actor_kind, approver_id, store_id, terminal_id, reason
                        FROM audit.audit_event WHERE entity_id = :entityId""")
                .param("entityId", entityId).query().singleRow();
    }

    private long countRows() {
        return jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE entity_id = :id")
                .param("id", entityId).query(Long.class).single();
    }

    private AuditEntry entry(String action, Map<String, Object> details) {
        return new AuditEntry(actorId, "ACCOUNT", approverId, action, "Store", entityId, storeId, terminalId,
                "customer asked", details);
    }
}
