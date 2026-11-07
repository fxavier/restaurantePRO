package com.xavier.backend.inventory.service;

import com.xavier.backend.inventory.exception.InventoryNotFoundException;
import com.xavier.backend.organization.BusinessDays;
import com.xavier.backend.organization.OrganizationApi;
import com.xavier.backend.organization.model.dto.StoreSummary;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** The business day an instant belongs to in a store (its zone and cut-off), for movements made by hand. */
@Service
public class BusinessDates {

    private final OrganizationApi organization;

    BusinessDates(OrganizationApi organization) {
        this.organization = organization;
    }

    public LocalDate of(UUID storeId, Instant instant) {
        StoreSummary store = organization.findStore(storeId)
                .orElseThrow(() -> new InventoryNotFoundException("Store", storeId));
        return BusinessDays.of(instant, store.timezone(), store.businessDayCutoff());
    }
}
