package com.xavier.backend.catalog.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.catalog.exception.CatalogCodeTakenException;
import com.xavier.backend.catalog.exception.CatalogEntryNotFoundException;
import com.xavier.backend.catalog.model.TaxCategory;
import com.xavier.backend.catalog.model.TaxRate;
import com.xavier.backend.catalog.model.dto.TaxCategoryCreateRequest;
import com.xavier.backend.catalog.model.dto.TaxCategorySummary;
import com.xavier.backend.catalog.model.dto.TaxCategoryUpdateRequest;
import com.xavier.backend.catalog.model.dto.TaxRateCreateRequest;
import com.xavier.backend.catalog.model.dto.TaxRateSummary;
import com.xavier.backend.catalog.repository.TaxCategoryRepository;
import com.xavier.backend.catalog.repository.TaxRateRepository;
import com.xavier.backend.shared.web.PageResponse;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tax categories and their dated rates are company-wide: installation-wide {@code CATALOG_MANAGE}. */
@Service
public class TaxService {

    private final TaxCategoryRepository categories;
    private final TaxRateRepository rates;
    private final AccessPolicy accessPolicy;
    private final CatalogAudit audit;
    private final Clock clock;

    TaxService(TaxCategoryRepository categories, TaxRateRepository rates, AccessPolicy accessPolicy,
               CatalogAudit audit, Clock clock) {
        this.categories = categories;
        this.rates = rates;
        this.accessPolicy = accessPolicy;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public TaxCategorySummary create(TaxCategoryCreateRequest request) {
        accessPolicy.require(Permission.CATALOG_MANAGE);
        TaxCategory category = save(TaxCategory.create(request.code(), request.name(), clock.instant()));
        audit.record("TAX_CATEGORY_CREATED", "TaxCategory", category.getId(), null, Map.of("code", category.getCode()));
        return summary(category);
    }

    @Transactional
    public TaxCategorySummary update(UUID id, TaxCategoryUpdateRequest request) {
        accessPolicy.require(Permission.CATALOG_MANAGE);
        TaxCategory category = category(id);
        OptimisticLocking.requireVersion(TaxCategory.class, id, category.getVersion(), request.version());
        category.update(request.code(), request.name(), clock.instant());
        save(category);
        audit.record("TAX_CATEGORY_UPDATED", "TaxCategory", id, null, Map.of("code", category.getCode()));
        return summary(category);
    }

    /** A rate change is a new rate from an instant on; existing rates are never edited. */
    @Transactional
    public TaxRateSummary addRate(UUID categoryId, TaxRateCreateRequest request) {
        accessPolicy.require(Permission.CATALOG_MANAGE);
        TaxCategory category = category(categoryId);
        TaxRate rate = UniqueConstraint.saveAndFlush(rates,
                TaxRate.of(categoryId, request.ratePercent(), request.effectiveFrom(), clock.instant()),
                () -> new CatalogCodeTakenException("A rate of tax category " + category.getCode() + " starting at "
                        + request.effectiveFrom()), "tax_rate_category_effective_uk");
        audit.record("TAX_RATE_ADDED", "TaxRate", rate.getId(), null, Map.of("taxCategoryId", categoryId.toString(),
                "ratePercent", rate.getRatePercent().toPlainString(), "effectiveFrom", rate.getEffectiveFrom().toString()));
        return rate.toSummary();
    }

    @Transactional(readOnly = true)
    public TaxCategorySummary get(UUID id) {
        accessPolicy.require(Permission.CATALOG_MANAGE);
        return summary(category(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<TaxCategorySummary> list(Pageable pageable) {
        accessPolicy.require(Permission.CATALOG_MANAGE);
        return PageResponse.from(categories.findAll(pageable).map(this::summary));
    }

    private TaxCategorySummary summary(TaxCategory category) {
        return category.toSummary(rates.findAllByTaxCategoryIdOrderByEffectiveFrom(category.getId()));
    }

    private TaxCategory save(TaxCategory category) {
        return UniqueConstraint.saveAndFlush(categories, category,
                () -> new CatalogCodeTakenException("Tax category code '" + category.getCode() + "'"),
                "tax_category_code_uk");
    }

    private TaxCategory category(UUID id) {
        return categories.findById(id).orElseThrow(() -> new CatalogEntryNotFoundException("Tax category", id));
    }
}
