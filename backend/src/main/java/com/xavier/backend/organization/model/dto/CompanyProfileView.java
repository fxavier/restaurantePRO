package com.xavier.backend.organization.model.dto;

import com.xavier.backend.organization.model.CompanyProfile;
import java.util.Currency;

/** The company profile. {@code version} is sent back on update (optimistic locking, D17). */
public record CompanyProfileView(String name, String taxId, String country, Currency currency, long version) {

    public static CompanyProfileView from(CompanyProfile company) {
        return new CompanyProfileView(company.getName(), company.getTaxId(), company.getCountry(),
                company.getCurrency(), company.getVersion());
    }
}
