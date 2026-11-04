package com.xavier.backend.organization.model;

import com.xavier.backend.organization.exception.CurrencyLockedException;
import com.xavier.backend.organization.exception.InvalidCompanyProfileException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Currency;
import java.util.Locale;
import java.util.Set;

/**
 * The single legal entity of this installation (spec §2, §8.2, D30). Exactly one row: the primary key is
 * the constant {@code TRUE} and the table checks it, so a second company cannot exist.
 */
@Entity
@Table(schema = "organization", name = "company_profile")
public class CompanyProfile {

    private static final Set<String> ISO_COUNTRIES = Locale.getISOCountries(Locale.IsoCountryCode.PART1_ALPHA2);

    @Id
    private Boolean id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 50)
    private String taxId;

    @Column(nullable = false, length = 2)
    private String country;

    @Column(nullable = false, length = 3)
    private String currency;

    @Version
    private Long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected CompanyProfile() {
        // JPA
    }

    public static CompanyProfile create(String name, String taxId, String country, String currency, Instant now) {
        CompanyProfile company = new CompanyProfile();
        company.id = Boolean.TRUE;
        company.createdAt = now;
        company.apply(name, taxId, country, currency, now);
        return company;
    }

    /** Replaces the profile. The currency is locked once a store exists (single currency, spec §8.4). */
    public void update(String name, String taxId, String country, String currency, boolean storesExist,
                       Instant now) {
        if (storesExist && !this.currency.equals(currency)) {
            throw new CurrencyLockedException();
        }
        apply(name, taxId, country, currency, now);
    }

    private void apply(String name, String taxId, String country, String currency, Instant now) {
        this.name = requireText(name, "name");
        this.taxId = requireText(taxId, "taxId");
        this.country = requireCountry(country);
        this.currency = requireCurrency(currency).getCurrencyCode();
        this.updatedAt = now;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidCompanyProfileException(field + " is required");
        }
        return value.strip();
    }

    private static String requireCountry(String code) {
        if (code == null || !ISO_COUNTRIES.contains(code)) {
            throw new InvalidCompanyProfileException(
                    "country '" + code + "' is not an ISO-3166 alpha-2 code (e.g. MZ)");
        }
        return code;
    }

    private static Currency requireCurrency(String code) {
        Currency parsed;
        try {
            parsed = Currency.getInstance(code == null ? "" : code);
        } catch (IllegalArgumentException e) {
            throw new InvalidCompanyProfileException("currency '" + code + "' is not an ISO-4217 code (e.g. MZN)");
        }
        if (parsed.getDefaultFractionDigits() < 0) {
            throw new InvalidCompanyProfileException(
                    "currency '" + code + "' has no minor units, so it cannot hold money amounts");
        }
        return parsed;
    }

    public String getName() {
        return name;
    }

    public String getTaxId() {
        return taxId;
    }

    public String getCountry() {
        return country;
    }

    public Currency getCurrency() {
        return Currency.getInstance(currency);
    }

    public Long getVersion() {
        return version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
