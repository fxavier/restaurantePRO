package com.xavier.backend.catalog.controller;

import static com.xavier.backend.catalog.Actors.admin;
import static com.xavier.backend.catalog.Actors.inStore;
import static com.xavier.backend.catalog.CatalogFixtures.CATALOG;
import static com.xavier.backend.catalog.CatalogFixtures.code;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.catalog.CatalogFixtures;
import com.xavier.backend.catalog.CatalogModuleTest;
import com.xavier.backend.catalog.Race;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@CatalogModuleTest
class UnitAndTaxHttpTest {

    static final String UNITS = CATALOG + "/units";
    static final String TAX = CATALOG + "/tax-categories";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Test
    void unitsAreCreatedUpdatedAndAuditedWithUniqueCodes() throws Exception {
        String code = code("U");
        UUID unit = CatalogFixtures.id(fixtures().created(post(UNITS).with(admin(Permission.CATALOG_MANAGE)),
                "{\"code\": \"%s\", \"name\": \"Kilogram\"}".formatted(code)));

        mvc.perform(post(UNITS).with(admin(Permission.CATALOG_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\": \"%s\", \"name\": \"x\"}".formatted(code)))
                .andExpect(status().isConflict());
        mvc.perform(put(UNITS + "/" + unit).with(admin(Permission.CATALOG_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\": \"%s\", \"name\": \"Kg\", \"version\": 0}".formatted(code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Kg"));
        mvc.perform(get(UNITS + "/" + unit).with(admin(Permission.CATALOG_MANAGE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(get(UNITS).with(admin(Permission.CATALOG_MANAGE))).andExpect(status().isOk());

        assertThat(auditCount("Unit", unit)).isEqualTo(2);
    }

    @Test
    void aTaxCategoryKeepsItsRatesWithTheirEffectiveInstants() throws Exception {
        UUID category = CatalogFixtures.id(fixtures().created(post(TAX).with(admin(Permission.CATALOG_MANAGE)),
                "{\"code\": \"%s\", \"name\": \"VAT normal\"}".formatted(code("T"))));

        fixtures().created(post(TAX + "/" + category + "/rates").with(admin(Permission.CATALOG_MANAGE)),
                "{\"ratePercent\": 16, \"effectiveFrom\": \"2024-01-01T00:00:00Z\"}");
        fixtures().created(post(TAX + "/" + category + "/rates").with(admin(Permission.CATALOG_MANAGE)),
                "{\"ratePercent\": 17.5, \"effectiveFrom\": \"2027-01-01T00:00:00Z\"}");

        mvc.perform(get(TAX + "/" + category).with(admin(Permission.CATALOG_MANAGE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rates.length()").value(2))
                .andExpect(jsonPath("$.rates[0].ratePercent").value(16))
                .andExpect(jsonPath("$.rates[0].effectiveFrom").value("2024-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.rates[1].ratePercent").value(17.5));
        assertThat(auditCount("TaxCategory", category)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE action = 'TAX_RATE_ADDED' AND entity_type = 'TaxRate'"
                + " AND details ->> 'taxCategoryId' = :id").param("id", category.toString()).query(Long.class).single())
                .isEqualTo(2);
    }

    @Test
    void twoRatesOfOneCategoryCannotStartAtTheSameInstantEvenWhenRacing() throws Exception {
        UUID category = fixtures().taxCategory("16");
        String rate = "{\"ratePercent\": 10, \"effectiveFrom\": \"2030-01-01T00:00:00Z\"}";

        assertThat(Race.twice(() -> mvc.perform(post(TAX + "/" + category + "/rates")
                        .with(admin(Permission.CATALOG_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(rate))
                .andReturn().getResponse().getStatus())).containsExactlyInAnyOrder(201, 409);
    }

    @Test
    void aRateOutsideZeroToHundredPercentIsRejected() throws Exception {
        UUID category = fixtures().taxCategory("16");

        for (String percent : new String[] {"-1", "100.01", "12.12345"}) {
            mvc.perform(post(TAX + "/" + category + "/rates").with(admin(Permission.CATALOG_MANAGE)).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"ratePercent\": %s, \"effectiveFrom\": \"2030-01-01T00:00:00Z\"}".formatted(percent)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void aRateForAnUnknownCategoryIsNotFound() throws Exception {
        mvc.perform(post(TAX + "/" + UUID.randomUUID() + "/rates").with(admin(Permission.CATALOG_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ratePercent\": 5, \"effectiveFrom\": \"2030-01-01T00:00:00Z\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void unitsAndTaxDataNeedInstallationWideCatalogManage() throws Exception {
        UUID store = fixtures().store();

        mvc.perform(post(UNITS).with(inStore(store, Permission.CATALOG_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\": \"%s\", \"name\": \"x\"}".formatted(code("U"))))
                .andExpect(status().isForbidden());
        mvc.perform(post(TAX).with(admin(Permission.CATALOG_VIEW)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\": \"%s\", \"name\": \"x\"}".formatted(code("T"))))
                .andExpect(status().isForbidden());
    }

    private long auditCount(String type, UUID id) {
        return jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE entity_type = :type AND entity_id = :id")
                .param("type", type).param("id", id.toString()).query(Long.class).single();
    }

    private CatalogFixtures fixtures() {
        return new CatalogFixtures(mvc);
    }
}
