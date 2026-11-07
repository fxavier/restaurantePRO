package com.xavier.backend.catalog.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.catalog.exception.CatalogCodeTakenException;
import com.xavier.backend.catalog.exception.CatalogEntryNotFoundException;
import com.xavier.backend.catalog.exception.CatalogRuleException;
import com.xavier.backend.catalog.exception.InvalidCatalogEntryException;
import com.xavier.backend.catalog.model.ItemLine;
import com.xavier.backend.catalog.model.Macro;
import com.xavier.backend.catalog.model.dto.ItemKind;
import com.xavier.backend.catalog.model.dto.ItemLineRequest;
import com.xavier.backend.catalog.model.dto.MacroCreateRequest;
import com.xavier.backend.catalog.model.dto.MacroSummary;
import com.xavier.backend.catalog.model.dto.MacroUpdateRequest;
import com.xavier.backend.catalog.repository.MacroRepository;
import com.xavier.backend.shared.persistence.OptimisticLocking;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Macros (D109) are company-wide: installation-wide {@code CATALOG_MANAGE}. They are frozen into every release. */
@Service
public class MacroService {

    private final MacroRepository macros;
    private final ItemLines itemLines;
    private final AccessPolicy accessPolicy;
    private final CatalogAudit audit;
    private final Clock clock;

    MacroService(MacroRepository macros, ItemLines itemLines, AccessPolicy accessPolicy, CatalogAudit audit,
                 Clock clock) {
        this.macros = macros;
        this.itemLines = itemLines;
        this.accessPolicy = accessPolicy;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<MacroSummary> list() {
        accessPolicy.require(Permission.CATALOG_MANAGE);
        return macros.findAllByOrderByCode().stream().map(Macro::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public MacroSummary get(UUID id) {
        accessPolicy.require(Permission.CATALOG_MANAGE);
        return macro(id).toSummary();
    }

    @Transactional
    public MacroSummary create(MacroCreateRequest request) {
        accessPolicy.require(Permission.CATALOG_MANAGE);
        Macro macro = save(Macro.create(request.code(), request.name(), entries(request.entries()), clock.instant()));
        audit("MACRO_CREATED", macro);
        return macro.toSummary();
    }

    @Transactional
    public MacroSummary update(UUID id, MacroUpdateRequest request) {
        accessPolicy.require(Permission.CATALOG_MANAGE);
        Macro macro = macro(id);
        OptimisticLocking.requireVersion(Macro.class, id, macro.getVersion(), request.version());
        macro.update(request.code(), request.name(), entries(request.entries()), clock.instant());
        save(macro);
        audit("MACRO_UPDATED", macro);
        return macro.toSummary();
    }

    /** A quick page entry naming the macro keeps it (foreign key): 409. */
    @Transactional
    public void delete(UUID id, long version) {
        accessPolicy.require(Permission.CATALOG_MANAGE);
        Macro macro = macro(id);
        OptimisticLocking.requireVersion(Macro.class, id, macro.getVersion(), version);
        macros.delete(macro);
        try {
            macros.flush();
        } catch (DataIntegrityViolationException e) {
            if (UniqueConstraints.isViolated(e, "quick_page_entry_macro_fk")) {
                throw new CatalogCodeTakenException("Macro " + macro.getCode() + " (on a quick page)");
            }
            throw e;
        }
        audit("MACRO_DELETED", macro);
    }

    /** @throws CatalogRuleException (422 {@code MENU_NOT_ALLOWED}) when an entry names a MENU item */
    private List<ItemLine> entries(List<ItemLineRequest> requests) {
        ItemLines.Resolved resolved = itemLines.resolve(requests, InvalidCatalogEntryException::new);
        resolved.anyOfKind(ItemKind.MENU).ifPresent(menu -> {
            throw CatalogRuleException.menuNotAllowed(menu.getCode(), "a macro");
        });
        return resolved.lines();
    }

    private Macro save(Macro macro) {
        return UniqueConstraints.saveAndFlush(macros, macro,
                () -> new CatalogCodeTakenException("Macro code '" + macro.getCode() + "'"), "macro_code_uk");
    }

    private Macro macro(UUID id) {
        return macros.findById(id).orElseThrow(() -> new CatalogEntryNotFoundException("Macro", id));
    }

    private void audit(String action, Macro macro) {
        audit.record(action, "Macro", macro.getId(), null, Map.of("code", macro.getCode()));
    }
}
