package com.xavier.backend.reporting;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.cash.CashFixtures;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Reports are store-scoped (D42 (a): the store is an input, so it is authorized before any read), take a range of
 * business dates of at most 366 days, and accept only their own groupings.
 */
@ReportingModuleTest
class ReportAccessHttpTest {

    static final LocalDate DAY = LocalDate.of(2026, 11, 5);

    @Autowired
    MockMvc mvc;

    @Autowired
    ReportingFixtures reporting;

    UUID store;

    @BeforeEach
    void aStore() {
        store = UUID.randomUUID();
    }

    @Test
    void aReportOfAnotherStoreIsForbidden() throws Exception {
        sales(DAY, DAY, "day", CashFixtures.account(UUID.randomUUID(), Permission.REPORT_VIEW))
                .andExpect(status().isForbidden());
        mvc.perform(get(ReportingFixtures.REPORTS + "/status").param("storeId", store.toString())
                .with(CashFixtures.account(UUID.randomUUID(), Permission.REPORT_VIEW)))
                .andExpect(status().isForbidden());
    }

    @Test
    void aRebuildNeedsReportRebuild() throws Exception {
        mvc.perform(CashFixtures.idem(post(ReportingFixtures.REPORTS + "/rebuild"))
                        .with(CashFixtures.account(store, Permission.REPORT_VIEW))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeId\": \"%s\", \"from\": \"%s\", \"to\": \"%s\"}".formatted(store, DAY, DAY)))
                .andExpect(status().isForbidden());
    }

    @Test
    void anUnknownStoreIsNotFound() throws Exception {
        sales(DAY, DAY, "day", CashFixtures.account(null, Permission.REPORT_VIEW)).andExpect(status().isNotFound());
    }

    @Test
    void aReversedOrTooLongRangeIsRefused() throws Exception {
        RequestPostProcessor viewer = CashFixtures.account(store, Permission.REPORT_VIEW);
        sales(DAY, DAY.minusDays(1), "day", viewer).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("RANGE_REVERSED"));
        sales(DAY.minusDays(366), DAY, "day", viewer).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("RANGE_TOO_LARGE"))
                .andExpect(jsonPath("$.maxDays").value(366));
    }

    @Test
    void anUnknownGroupingOrLimitIsABadRequest() throws Exception {
        RequestPostProcessor viewer = CashFixtures.account(store, Permission.REPORT_VIEW);
        sales(DAY, DAY, "weather", viewer).andExpect(status().isBadRequest());
        mvc.perform(get(ReportingFixtures.REPORTS + "/top-items").param("storeId", store.toString())
                        .param("from", DAY.toString()).param("to", DAY.toString()).param("limit", "101").with(viewer))
                .andExpect(status().isBadRequest());
    }

    @Test
    void marginsAndDiscountsAreStoreScopedAndAcceptOnlyTheirGroupings() throws Exception {
        RequestPostProcessor elsewhere = CashFixtures.account(UUID.randomUUID(), Permission.REPORT_VIEW);
        RequestPostProcessor viewer = CashFixtures.account(store, Permission.REPORT_VIEW);
        report("margins", "item", elsewhere).andExpect(status().isForbidden());
        report("discounts", "origin", elsewhere).andExpect(status().isForbidden());
        report("margins", "item", viewer).andExpect(status().isNotFound());
        report("discounts", "approver", viewer).andExpect(status().isNotFound());
        report("margins", "origin", viewer).andExpect(status().isBadRequest());
        report("discounts", "family", viewer).andExpect(status().isBadRequest());
    }

    private ResultActions report(String report, String groupBy, RequestPostProcessor who) throws Exception {
        return mvc.perform(get(ReportingFixtures.REPORTS + "/" + report).param("storeId", store.toString())
                .param("from", DAY.toString()).param("to", DAY.toString()).param("groupBy", groupBy).with(who));
    }

    private ResultActions sales(LocalDate from, LocalDate to, String groupBy, RequestPostProcessor who)
            throws Exception {
        return mvc.perform(get(ReportingFixtures.REPORTS + "/sales").param("storeId", store.toString())
                .param("from", from.toString()).param("to", to.toString()).param("groupBy", groupBy).with(who));
    }
}
