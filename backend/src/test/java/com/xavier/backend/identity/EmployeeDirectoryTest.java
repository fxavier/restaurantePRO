package com.xavier.backend.identity;

import static org.assertj.core.api.Assertions.assertThat;

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

    @Test
    void noIdsMeanNoSummaries() {
        assertThat(directory.summaries(List.of())).isEmpty();
    }
}
