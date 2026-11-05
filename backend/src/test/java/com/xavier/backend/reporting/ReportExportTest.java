package com.xavier.backend.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.cash.CashFixtures;
import com.xavier.backend.settlement.SettlementFixtures;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;

/**
 * A report exported as CSV (D86, P37) has the report's rows, with labels, and is audited {@code REPORT_EXPORTED}:
 * exporting needs {@code REPORT_EXPORT} (and {@code REPORT_VIEW}, as viewing it does) in the store.
 */
@ReportingModuleTest
class ReportExportTest {

    @Autowired
    SettlementFixtures sales;

    @Autowired
    ReportingFixtures reporting;

    @Autowired
    MockMvc mvc;

    Sale sale;
    LocalDate day;

    @BeforeEach
    void aSoldDay() throws Exception {
        sale = sales.sale("2");
        sales.payAndClose(sale);
        reporting.awaitSalesRecord(reporting.issuedDocument(sale.order()));
        day = reporting.businessDayOf(sale.order());
    }

    @Test
    void aSalesReportIsExportedAsCsvAndAudited() throws Exception {
        String csv = export("sales", "day", Permission.REPORT_VIEW, Permission.REPORT_EXPORT)
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andReturn().getResponse().getContentAsString();

        assertThat(csv.split("\r\n")).containsExactly(
                "key,label,documents,quantity,gross,net,tax",
                day + "," + day + ",1,2.000,25.00,21.55,3.45",
                "TOTAL,,1,2.000,25.00,21.55,3.45");
        Map<String, Object> audit = reporting.row("""
                SELECT store_id, details::text AS details FROM audit.audit_event
                WHERE action = 'REPORT_EXPORTED' AND store_id = ?""", sale.store());
        assertThat((String) audit.get("details")).contains("\"report\": \"sales\"").contains("\"rows\": 1")
                .contains("\"groupBy\": \"day\"");
    }

    @Test
    void exportingNeedsReportExportInTheStore() throws Exception {
        export("sales", "day", Permission.REPORT_VIEW).andExpect(status().isForbidden());

        assertThat(reporting.count("SELECT count(*) FROM audit.audit_event WHERE action = 'REPORT_EXPORTED' "
                + "AND store_id = ?", sale.store())).isZero();
    }

    @Test
    void anExportWithAnUnknownGroupingIsRefused() throws Exception {
        export("sales", "weather", Permission.REPORT_VIEW, Permission.REPORT_EXPORT)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_EXPORT_PARAMS"));
    }

    private ResultActions export(String report, String groupBy, Permission... permissions) throws Exception {
        return mvc.perform(post(ReportingFixtures.REPORTS + "/exports")
                .with(CashFixtures.account(sale.store(), permissions))
                .with(SecurityMockMvcRequestPostProcessors.csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"report": "%s", "params": {"storeId": "%s", "from": "%s", "to": "%s", "groupBy": "%s"}}"""
                        .formatted(report, sale.store(), day, day, groupBy)));
    }
}
