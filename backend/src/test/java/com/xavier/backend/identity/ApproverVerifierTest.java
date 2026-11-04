package com.xavier.backend.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.access.Permission;
import com.xavier.backend.identity.IdentityFixtures.Employee;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/** D28: a critical action carries a supervisor's code + PIN; the server checks the permission in that store. */
@IdentityTest
class ApproverVerifierTest {

    static final String PIN = "7391";

    @Autowired
    ApproverVerifier approvers;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate transaction;

    IdentityFixtures fixtures;
    UUID storeA;

    @BeforeEach
    void store() throws Exception {
        fixtures = new IdentityFixtures(mvc);
        storeA = fixtures.store();
    }

    @Test
    void aManagerOfTheStoreApprovesAndTheirIdIsReturned() throws Exception {
        Employee manager = fixtures.employee(PIN, storeA, IdentityFixtures.MANAGER);

        assertThat(approvers.verify(manager.code(), PIN, Permission.CATALOG_PUBLISH, storeA)).isEqualTo(manager.id());
    }

    @Test
    void anApproverWithoutThePermissionInThatStoreIsRefused() throws Exception {
        Employee waiter = fixtures.employee(PIN, storeA, IdentityFixtures.WAITER);
        Employee managerElsewhere = fixtures.employee(PIN, fixtures.store(), IdentityFixtures.MANAGER);

        assertThatThrownBy(() -> approvers.verify(waiter.code(), PIN, Permission.CATALOG_PUBLISH, storeA))
                .isInstanceOf(ApprovalDeniedException.class).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> approvers.verify(managerElsewhere.code(), PIN, Permission.CATALOG_PUBLISH, storeA))
                .isInstanceOf(ApprovalDeniedException.class);
        assertThatThrownBy(() -> approvers.verify("nobody", PIN, Permission.CATALOG_PUBLISH, storeA))
                .isInstanceOf(ApprovalDeniedException.class);
    }

    @Test
    void wrongPinsCountTowardLockoutEvenWhenTheCallersTransactionRollsBack() throws Exception {
        Employee manager = fixtures.employee(PIN, storeA, IdentityFixtures.MANAGER);

        for (int i = 0; i < 5; i++) {
            transaction.executeWithoutResult(tx -> {
                assertThatThrownBy(() -> approvers.verify(manager.code(), "0000", Permission.CATALOG_PUBLISH, storeA))
                        .isInstanceOf(ApprovalDeniedException.class);
                tx.setRollbackOnly(); // the refused use case rolls back
            });
        }

        assertThat(jdbc.sql("SELECT locked FROM identity.employee WHERE id = :id").param("id", manager.id())
                .query(Boolean.class).single()).isTrue();
        assertThatThrownBy(() -> approvers.verify(manager.code(), PIN, Permission.CATALOG_PUBLISH, storeA))
                .isInstanceOf(ApprovalDeniedException.class);
    }
}
