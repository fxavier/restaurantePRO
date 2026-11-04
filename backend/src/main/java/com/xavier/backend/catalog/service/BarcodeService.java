package com.xavier.backend.catalog.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.catalog.exception.CatalogCodeTakenException;
import com.xavier.backend.catalog.exception.CatalogEntryNotFoundException;
import com.xavier.backend.catalog.exception.InvalidCatalogEntryException;
import com.xavier.backend.catalog.model.Barcode;
import com.xavier.backend.catalog.model.dto.BarcodeCreateRequest;
import com.xavier.backend.catalog.model.dto.BarcodeSummary;
import com.xavier.backend.catalog.model.dto.BarcodeUpdateRequest;
import com.xavier.backend.catalog.repository.BarcodeRepository;
import com.xavier.backend.catalog.repository.ItemRepository;
import com.xavier.backend.catalog.repository.ItemVariantRepository;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Barcodes are unique in the installation and company-wide: installation-wide {@code CATALOG_MANAGE}. */
@Service
public class BarcodeService {

    private final BarcodeRepository barcodes;
    private final ItemRepository items;
    private final ItemVariantRepository variants;
    private final AccessPolicy accessPolicy;
    private final CatalogAudit audit;
    private final Clock clock;

    BarcodeService(BarcodeRepository barcodes, ItemRepository items, ItemVariantRepository variants,
                   AccessPolicy accessPolicy, CatalogAudit audit, Clock clock) {
        this.barcodes = barcodes;
        this.items = items;
        this.variants = variants;
        this.accessPolicy = accessPolicy;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public BarcodeSummary create(BarcodeCreateRequest request) {
        accessPolicy.require(Permission.CATALOG_MANAGE);
        requireTarget(request.itemId(), request.variantId());
        Barcode barcode = save(Barcode.create(request.code(), request.itemId(), request.variantId(), clock.instant()));
        audit("BARCODE_CREATED", barcode);
        return barcode.toSummary();
    }

    @Transactional
    public BarcodeSummary update(UUID id, BarcodeUpdateRequest request) {
        accessPolicy.require(Permission.CATALOG_MANAGE);
        Barcode barcode = barcode(id);
        OptimisticLocking.requireVersion(Barcode.class, id, barcode.getVersion(), request.version());
        requireTarget(request.itemId(), request.variantId());
        barcode.update(request.code(), request.itemId(), request.variantId(), clock.instant());
        save(barcode);
        audit("BARCODE_UPDATED", barcode);
        return barcode.toSummary();
    }

    @Transactional(readOnly = true)
    public BarcodeSummary get(UUID id) {
        accessPolicy.require(Permission.CATALOG_MANAGE);
        return barcode(id).toSummary();
    }

    @Transactional(readOnly = true)
    public List<BarcodeSummary> listForItem(UUID itemId) {
        accessPolicy.require(Permission.CATALOG_MANAGE);
        return barcodes.findAllByItemIdOrderByCode(itemId).stream().map(Barcode::toSummary).toList();
    }

    private void requireTarget(UUID itemId, UUID variantId) {
        if (!items.existsById(itemId)) {
            throw new InvalidCatalogEntryException("Item " + itemId + " does not exist");
        }
        if (variantId != null && !variants.existsByIdAndItemId(variantId, itemId)) {
            throw new InvalidCatalogEntryException("Variant " + variantId + " is not a variant of item " + itemId);
        }
    }

    private Barcode save(Barcode barcode) {
        return UniqueConstraint.saveAndFlush(barcodes, barcode,
                () -> new CatalogCodeTakenException("Barcode '" + barcode.getCode() + "'"), "barcode_code_uk");
    }

    private Barcode barcode(UUID id) {
        return barcodes.findById(id).orElseThrow(() -> new CatalogEntryNotFoundException("Barcode", id));
    }

    private void audit(String action, Barcode barcode) {
        Map<String, Object> details = new HashMap<>();
        details.put("code", barcode.getCode());
        details.put("itemId", barcode.getItemId().toString());
        if (barcode.getVariantId() != null) {
            details.put("variantId", barcode.getVariantId().toString());
        }
        audit.record(action, "Barcode", barcode.getId(), null, details);
    }
}
