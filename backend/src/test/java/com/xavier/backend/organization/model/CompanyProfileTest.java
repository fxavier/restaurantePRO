package com.xavier.backend.organization.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.organization.exception.CurrencyLockedException;
import com.xavier.backend.organization.exception.InvalidCompanyProfileException;
import java.time.Instant;
import java.util.Currency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CompanyProfileTest {

    static final Instant NOW = Instant.parse("2026-11-04T10:00:00Z");

    @Test
    void createsTheCompanyWithIsoCountryAndCurrency() {
        CompanyProfile company = CompanyProfile.create("Restaurante Sol", "400123456", "MZ", "MZN", NOW);

        assertThat(company.getCountry()).isEqualTo("MZ");
        assertThat(company.getCurrency()).isEqualTo(Currency.getInstance("MZN"));
    }

    @ParameterizedTest(name = "{0}/{1} -> mentions {2}")
    @CsvSource({
            "ZZ, MZN, country",   // not an ISO-3166 alpha-2 code
            "mz, MZN, country",   // codes are upper case
            "MZ, ZZZ, currency",  // not an ISO-4217 code
            "MZ, XXX, currency",  // ISO code without minor units (fraction digits -1): no money amounts
            "MZ, XAU, currency"   // gold: fraction digits -1
    })
    void rejectsCodesThatAreNotUsableIsoCodes(String country, String currency, String field) {
        assertThatThrownBy(() -> CompanyProfile.create("Restaurante Sol", "400123456", country, currency, NOW))
                .isInstanceOf(InvalidCompanyProfileException.class)
                .hasMessageContaining(field);
    }

    @Test
    void theCurrencyCannotChangeOnceAStoreExists() {
        CompanyProfile company = CompanyProfile.create("Restaurante Sol", "400123456", "MZ", "MZN", NOW);

        assertThatThrownBy(() -> company.update("Restaurante Sol", "400123456", "PT", "EUR", true, NOW))
                .isInstanceOf(CurrencyLockedException.class);
        assertThat(company.getCurrency()).isEqualTo(Currency.getInstance("MZN"));
    }

    @Test
    void anUnusableCurrencyIsReportedAsSuchEvenWhenTheCurrencyIsLocked() {
        CompanyProfile company = CompanyProfile.create("Restaurante Sol", "400123456", "MZ", "MZN", NOW);

        assertThatThrownBy(() -> company.update("Restaurante Sol", "400123456", "MZ", "XXX", true, NOW))
                .isInstanceOf(InvalidCompanyProfileException.class)
                .hasMessageContaining("minor units");
    }

    @Test
    void theCurrencyCanChangeWhileThereAreNoStores() {
        CompanyProfile company = CompanyProfile.create("Restaurante Sol", "400123456", "MZ", "MZN", NOW);

        company.update("Sol Lda", "500999888", "PT", "EUR", false, NOW);

        assertThat(company.getName()).isEqualTo("Sol Lda");
        assertThat(company.getCurrency()).isEqualTo(Currency.getInstance("EUR"));
    }

    @Test
    void otherFieldsCanChangeWhenStoresExistIfTheCurrencyStaysTheSame() {
        CompanyProfile company = CompanyProfile.create("Restaurante Sol", "400123456", "MZ", "MZN", NOW);

        company.update("Sol Lda", "400123456", "MZ", "MZN", true, NOW);

        assertThat(company.getName()).isEqualTo("Sol Lda");
    }
}
