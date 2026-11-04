package com.xavier.backend.fiscal.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.fiscal.exception.SeriesNotFoundException;
import com.xavier.backend.fiscal.exception.SeriesTakenException;
import com.xavier.backend.fiscal.exception.UnknownStoreException;
import com.xavier.backend.fiscal.model.DocumentType;
import com.xavier.backend.fiscal.model.FiscalSeries;
import com.xavier.backend.fiscal.model.dto.SeriesCreateRequest;
import com.xavier.backend.fiscal.model.dto.SeriesView;
import com.xavier.backend.fiscal.repository.FiscalSeriesRepository;
import com.xavier.backend.organization.OrganizationApi;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import com.xavier.backend.shared.web.PageResponse;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Series use cases (spec §9). All need installation-wide {@code FISCAL_MANAGE}. There is deliberately no use case
 * that edits a series: the first number is set at creation (audited) and only issuing advances the next number.
 */
@Service
public class SeriesService {

    private static final String CODE_UK = "fiscal_series_code_uk";
    private static final String ACTIVE_UK = "fiscal_series_active_uk";

    private final FiscalSeriesRepository series;
    private final OrganizationApi organization;
    private final AccessPolicy accessPolicy;
    private final AuditLog auditLog;
    private final Clock clock;

    SeriesService(FiscalSeriesRepository series, OrganizationApi organization, AccessPolicy accessPolicy,
                  AuditLog auditLog, Clock clock) {
        this.series = series;
        this.organization = organization;
        this.accessPolicy = accessPolicy;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    @Transactional
    public SeriesView create(SeriesCreateRequest request) {
        accessPolicy.require(Permission.FISCAL_MANAGE);
        UUID storeId = request.storeId();
        if (!organization.storeExists(storeId)) {
            throw new UnknownStoreException(storeId);
        }
        String code = request.code().strip();
        if (series.existsByStoreIdAndDocumentTypeAndCode(storeId, DocumentType.SALE, code)) {
            throw codeTaken(code);
        }
        if (series.existsByStoreIdAndDocumentTypeAndActiveTrue(storeId, DocumentType.SALE)) {
            throw activeExists();
        }
        long firstNumber = request.firstNumber() == null ? 1 : request.firstNumber();
        FiscalSeries created = save(FiscalSeries.create(storeId, DocumentType.SALE, code, firstNumber,
                clock.instant()));
        auditLog.recordAsCurrentActor("FISCAL_SERIES_CREATED", "FiscalSeries", created.getId().toString(), storeId,
                null, null, Map.of("code", code, "documentType", DocumentType.SALE.name(),
                        "firstNumber", firstNumber));
        return created.toView();
    }

    @Transactional
    public SeriesView deactivate(UUID id) {
        accessPolicy.require(Permission.FISCAL_MANAGE);
        FiscalSeries found = series.findById(id).orElseThrow(() -> new SeriesNotFoundException(id));
        found.deactivate(clock.instant());
        series.saveAndFlush(found); // a concurrent issue bumped the version: 409, not a lost update
        auditLog.recordAsCurrentActor("FISCAL_SERIES_DEACTIVATED", "FiscalSeries", id.toString(),
                found.getStoreId(), null, null, Map.of("code", found.getCode(),
                        "nextNumber", found.getNextNumber()));
        return found.toView();
    }

    @Transactional(readOnly = true)
    public PageResponse<SeriesView> list(UUID storeId, Pageable pageable) {
        accessPolicy.require(Permission.FISCAL_MANAGE);
        return PageResponse.from((storeId == null ? series.findAll(pageable) : series.findByStoreId(storeId, pageable))
                .map(FiscalSeries::toView));
    }

    /** The checks above give the clear answer; a concurrent create that passed them too loses here (409). */
    private FiscalSeries save(FiscalSeries created) {
        try {
            return series.saveAndFlush(created);
        } catch (DataIntegrityViolationException e) {
            if (UniqueConstraints.isViolated(e, CODE_UK)) {
                throw codeTaken(created.getCode());
            }
            if (UniqueConstraints.isViolated(e, ACTIVE_UK)) {
                throw activeExists();
            }
            throw e;
        }
    }

    private static SeriesTakenException codeTaken(String code) {
        return new SeriesTakenException("The store already has a series " + code);
    }

    private static SeriesTakenException activeExists() {
        return new SeriesTakenException("The store already has an active SALE series; deactivate it first");
    }
}
