package com.xavier.backend.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.access.Permission;
import com.xavier.backend.identity.IdentityFixtures.Employee;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

/** Labels for exports (D66): the code and display name of employees, by id, without a session. */
@IdentityTest
class EmployeeDirectoryTest {

    @Autowired
    EmployeeDirectory directory;

    @Autowired
    MockMvc mvc;

    @Test
    void summariesNameTheKnownEmployeesAndSkipUnknownIds() throws Exception {
        IdentityFixtures fixtures = new IdentityFixtures(mvc);
        UUID store = fixtures.store();
        Employee first = fixtures.employee("7391", store, IdentityFixtures.WAITER);
        Employee second = fixtures.employee("7391", store, IdentityFixtures.MANAGER);
        UUID unknown = UUID.randomUUID();
        SecurityContextHolder.clearContext();

        Map<UUID, EmployeeSummary> summaries = directory.summaries(List.of(first.id(), second.id(), unknown,
                first.id()));

        assertThat(summaries).containsOnlyKeys(first.id(), second.id());
        assertThat(summaries.get(first.id())).isEqualTo(new EmployeeSummary(first.id(), first.code(), "Ana"));
    }

    @Autowired
    org.springframework.jdbc.core.simple.JdbcClient jdbc;

    /** S50: a courier must be an active employee assigned to the delivery's store; a PIN lockout does not matter. */
    @Test
    void anEmployeeIsActiveInAStoreOnlyWhenActiveAndAssignedThere() throws Exception {
        IdentityFixtures fixtures = new IdentityFixtures(mvc);
        UUID store = fixtures.store();
        UUID otherStore = fixtures.store();
        Employee assigned = fixtures.employee("7391", store, IdentityFixtures.WAITER);
        Employee inactive = fixtures.employee("7391", store, IdentityFixtures.WAITER);
        Employee locked = fixtures.employee("7391", store, IdentityFixtures.WAITER);
        jdbc.sql("UPDATE identity.employee SET active = false WHERE id = ?").param(inactive.id()).update();
        jdbc.sql("UPDATE identity.employee SET locked = true WHERE id = ?").param(locked.id()).update();
        SecurityContextHolder.clearContext();

        assertThat(directory.isActiveInStore(assigned.id(), store)).isTrue();
        assertThat(directory.isActiveInStore(assigned.id(), otherStore)).isFalse();
        assertThat(directory.isActiveInStore(inactive.id(), store)).isFalse();
        assertThat(directory.isActiveInStore(UUID.randomUUID(), store)).isFalse();
        assertThat(directory.isActiveInStore(locked.id(), store)).isTrue();
        assertThat(directory.isActiveInStore(null, store)).isFalse();
        assertThat(directory.isActiveInStore(assigned.id(), null)).isFalse();
    }

    /** Q4: a courier holds DELIVERY_COURIER through a role of their assignment in that store, not in another one. */
    @Test
    void anEmployeeHoldsAPermissionInAStoreThroughTheRoleAssignedThere() throws Exception {
        IdentityFixtures fixtures = new IdentityFixtures(mvc);
        UUID store = fixtures.store();
        UUID otherStore = fixtures.store();
        UUID driver = fixtures.role(Permission.DELIVERY_COURIER);
        Employee courier = fixtures.employee("7391", store, driver);
        Employee waiter = fixtures.employee("7391", store, IdentityFixtures.WAITER);
        Employee courierElsewhere = fixtures.employee("7391", otherStore, driver);
        Employee inactive = fixtures.employee("7391", store, driver);
        jdbc.sql("UPDATE identity.employee SET active = false WHERE id = ?").param(inactive.id()).update();
        SecurityContextHolder.clearContext();

        assertThat(directory.holdsInStore(courier.id(), store, Permission.DELIVERY_COURIER)).isTrue();
        assertThat(directory.holdsInStore(waiter.id(), store, Permission.DELIVERY_COURIER)).isFalse();
        assertThat(directory.holdsInStore(courierElsewhere.id(), store, Permission.DELIVERY_COURIER)).isFalse();
        assertThat(directory.holdsInStore(inactive.id(), store, Permission.DELIVERY_COURIER)).isFalse();
        assertThat(directory.holdsInStore(null, store, Permission.DELIVERY_COURIER)).isFalse();
        assertThat(directory.activeInStoreHolding(store, Permission.DELIVERY_COURIER))
                .extracting(EmployeeSummary::id).containsExactly(courier.id());
    }

    @Test
    void noIdsMeanNoSummaries() {
        assertThat(directory.summaries(List.of())).isEmpty();
    }
}
