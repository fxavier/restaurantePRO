package com.xavier.backend.organization.model.dto;

import java.util.Currency;

/** The company profile. {@code version} is sent back on update (optimistic locking, D17). */
public record CompanyProfileView(String name, String taxId, String country, Currency currency, long version) {
}
