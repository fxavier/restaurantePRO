package com.xavier.backend.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.identity.ApproverVerifier;
import com.xavier.backend.settlement.SettlementFixtures;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * A void is projected from {@code OrdersApi.voidFacts} in its order's business day (D85), and the voids report groups
 * it by reason, employee, item or approver, with its wasted and after-dispatch counts.
 */
@ReportingModuleTest
class VoidsReportTest {

    @Autowired
    SettlementFixtures sales;

    @Autowired
    ReportingFixtures reporting;

    @Autowired
    ApproverVerifier approvers;

    UUID manager;
    Sale sale;
    LocalDate day;

    @BeforeEach
    void aVoidedItem() throws Exception {
        manager = UUID.randomUUID();
        when(approvers.verify(any(), any(), eq(Permission.ORDER_VOID_APPROVE), any())).thenReturn(manager);
        sale = sales.sale("3");
        UUID line = sales.lineIds(sale.cashier(), sale.order()).getFirst();
        sales.settledVoid(sale.cashier(), sale.order(), sales.orderVersion(sale.cashier(), sale.order()), line, "1",
                UUID.randomUUID().toString()).andExpect(status().isCreated());
        UUID voidId = reporting.voidsOf(sale.order()).getFirst();
        reporting.awaitVoidRecord(voidId);
        day = reporting.businessDayOf(sale.order());
    }

    @Test
    void aVoidIsReportedByReasonWithItsWasteAndApprover() throws Exception {
        JsonNode byReason = reporting.report("voids", sale.store(), day, day, "groupBy", "reason");

        assertThat(byReason.get("rows")).hasSize(1);
        JsonNode row = byReason.get("rows").get(0);
        assertThat(row.get("key").asString()).isEqualTo("dropped");
        assertThat(row.get("voids").asLong()).isOne();
        assertThat(row.get("quantity").decimalValue()).isEqualByComparingTo("1");
        assertThat(row.get("gross").get("amount").decimalValue()).isEqualByComparingTo("12.50");
        assertThat(row.get("wasted").asLong()).isOne();
        assertThat(row.get("afterDispatch").asLong()).isZero(); // production is not in this context

        assertThat(reporting.report("voids", sale.store(), day, day, "groupBy", "approver").get("rows").get(0)
                .get("key").asString()).isEqualTo(manager.toString());
        assertThat(reporting.report("voids", sale.store(), day, day, "groupBy", "employee").get("rows").get(0)
                .get("key").asString()).isEqualTo(sale.cashier().employeeId().toString());
        assertThat(reporting.report("voids", sale.store(), day, day, "groupBy", "item").get("rows").get(0)
                .get("label").asString()).isNotBlank();
    }
}
