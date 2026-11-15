package com.xavier.backend.catalog.service;

import com.xavier.backend.catalog.exception.CatalogCodeTakenException;
import com.xavier.backend.catalog.exception.CatalogEntryNotFoundException;
import com.xavier.backend.catalog.exception.InvalidCatalogEntryException;
import com.xavier.backend.catalog.model.QuickPage;
import com.xavier.backend.catalog.model.QuickPageEntry;
import com.xavier.backend.catalog.model.dto.QuickPageCreateRequest;
import com.xavier.backend.catalog.model.dto.QuickPageEntryRequest;
import com.xavier.backend.catalog.model.dto.QuickPageSummary;
import com.xavier.backend.catalog.model.dto.QuickPageUpdateRequest;
import com.xavier.backend.catalog.repository.ItemVariantRepository;
import com.xavier.backend.catalog.repository.MacroRepository;
import com.xavier.backend.catalog.repository.QuickPageRepository;
import com.xavier.backend.shared.persistence.OptimisticLocking;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * POS quick pages (D109), per store or for every store ({@code CATALOG_MANAGE} there, or installation-wide). Each
 * entry names an existing item, variant or macro; release validation checks that the items are in the release.
 */
@Service
public class QuickPageService {

    private static final String TABLE = "catalog.quick_page";

    private final QuickPageRepository pages;
    private final ItemLines itemLines;
    private final ItemVariantRepository variants;
    private final MacroRepository macros;
    private final StoreScopedAccess access;
    private final CatalogAudit audit;
    private final Clock clock;

    QuickPageService(QuickPageRepository pages, ItemLines itemLines, ItemVariantRepository variants,
                     MacroRepository macros, StoreScopedAccess access, CatalogAudit audit, Clock clock) {
        this.pages = pages;
        this.itemLines = itemLines;
        this.variants = variants;
        this.macros = macros;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    /** {@code storeId} given: the pages that store sees (its own and every store's); else the every-store pages. */
    @Transactional(readOnly = true)
    public List<QuickPageSummary> list(UUID storeId) {
        access.require(storeId);
        return (storeId == null ? pages.findAllByStoreIdIsNullOrderByPositionAscCodeAsc() : pages.findAllForStore(storeId))
                .stream().map(QuickPage::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public QuickPageSummary get(UUID id) {
        access.requireExisting(pages.findStoreById(id), TABLE, "Quick page", id);
        return page(id).toSummary();
    }

    @Transactional
    public QuickPageSummary create(QuickPageCreateRequest request) {
        access.requireForNew(request.storeId());
        QuickPage page = save(QuickPage.create(request.storeId(), request.code(), request.name(), request.position(),
                entries(request.entries()), clock.instant()));
        audit("QUICK_PAGE_CREATED", page);
        return page.toSummary();
    }

    @Transactional
    public QuickPageSummary update(UUID id, QuickPageUpdateRequest request) {
        access.requireExisting(pages.findStoreById(id), TABLE, "Quick page", id);
        QuickPage page = page(id);
        OptimisticLocking.requireVersion(QuickPage.class, id, page.getVersion(), request.version());
        page.update(request.code(), request.name(), request.position(), entries(request.entries()), clock.instant());
        save(page);
        audit("QUICK_PAGE_UPDATED", page);
        return page.toSummary();
    }

    @Transactional
    public void delete(UUID id, long version) {
        access.requireExisting(pages.findStoreById(id), TABLE, "Quick page", id);
        QuickPage page = page(id);
        OptimisticLocking.requireVersion(QuickPage.class, id, page.getVersion(), version);
        pages.delete(page);
        pages.flush();
        audit("QUICK_PAGE_DELETED", page);
    }

    private List<QuickPageEntry> entries(List<QuickPageEntryRequest> requests) {
        return requests.stream().map(request -> {
            QuickPageEntry entry = new QuickPageEntry(request.itemId(), request.variantId(), request.macroId());
            if (request.itemId() != null) {
                itemLines.require(request.itemId(), null, InvalidCatalogEntryException::new);
            } else if (request.variantId() != null && !variants.existsById(request.variantId())) {
                throw new InvalidCatalogEntryException("Variant " + request.variantId() + " does not exist");
            } else if (request.macroId() != null && !macros.existsById(request.macroId())) {
                throw new InvalidCatalogEntryException("Macro " + request.macroId() + " does not exist");
            }
            return entry;
        }).toList();
    }

    private QuickPage save(QuickPage page) {
        return UniqueConstraints.saveAndFlush(pages, page,
                () -> new CatalogCodeTakenException("Quick page code '" + page.getCode() + "' in this store"),
                "quick_page_store_code_uk");
    }

    private QuickPage page(UUID id) {
        return pages.findById(id).orElseThrow(() -> new CatalogEntryNotFoundException("Quick page", id));
    }

    private void audit(String action, QuickPage page) {
        Map<String, Object> details = new HashMap<>();
        details.put("code", page.getCode());
        audit.record(action, "QuickPage", page.getId(), page.getStoreId(), details);
    }
}
