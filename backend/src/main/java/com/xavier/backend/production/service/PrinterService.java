package com.xavier.backend.production.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.organization.OrganizationApi;
import com.xavier.backend.production.exception.ProductionConflictException;
import com.xavier.backend.production.exception.ProductionNotFoundException;
import com.xavier.backend.production.model.PrintJob;
import com.xavier.backend.production.model.PrintJobStatus;
import com.xavier.backend.production.model.Printer;
import com.xavier.backend.production.model.PrinterMode;
import com.xavier.backend.production.model.PrinterProfile;
import com.xavier.backend.production.model.dto.PrinterCreateRequest;
import com.xavier.backend.production.model.dto.PrinterPresetView;
import com.xavier.backend.production.model.dto.PrinterModeRequest;
import com.xavier.backend.production.model.dto.PrinterUpdateRequest;
import com.xavier.backend.production.model.dto.PrinterView;
import com.xavier.backend.production.repository.PrintJobRepository;
import com.xavier.backend.production.repository.PrinterRepository;
import com.xavier.backend.shared.notify.ChangeNotifier;
import com.xavier.backend.shared.notify.ChangeTopic;
import com.xavier.backend.shared.persistence.OptimisticLocking;
import com.xavier.backend.shared.persistence.StoreProbe;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import com.xavier.backend.shared.web.PageResponse;
import java.time.Clock;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Printer configuration and modes (BackOffice): {@code PRODUCTION_MANAGE} in the printer's store. */
@Service
public class PrinterService {

    static final String ENTITY = "Printer";

    private final PrinterRepository printers;
    private final PrintJobRepository jobs;
    private final OrganizationApi organization;
    private final AccessPolicy accessPolicy;
    private final StoreProbe probe;
    private final AuditLog auditLog;
    private final ChangeNotifier notifier;
    private final Clock clock;

    PrinterService(PrinterRepository printers, PrintJobRepository jobs, OrganizationApi organization,
                   AccessPolicy accessPolicy, StoreProbe probe, AuditLog auditLog, ChangeNotifier notifier,
                   Clock clock) {
        this.printers = printers;
        this.jobs = jobs;
        this.organization = organization;
        this.accessPolicy = accessPolicy;
        this.probe = probe;
        this.auditLog = auditLog;
        this.notifier = notifier;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<PrinterView> list(UUID storeId, Pageable pageable) {
        accessPolicy.require(Permission.PRODUCTION_MANAGE, storeId);
        return PageResponse.from(printers.findByStoreId(storeId, pageable).map(Printer::toView));
    }

    /** The presets of D154; they only fill the form. */
    public List<PrinterPresetView> presets() {
        accessPolicy.requireInAnyStore(Permission.PRODUCTION_MANAGE);
        return PrinterProfile.presets().stream().map(PrinterPresetView::from).toList();
    }

    @Transactional
    public PrinterView create(PrinterCreateRequest request) {
        accessPolicy.require(Permission.PRODUCTION_MANAGE, request.storeId());
        if (!organization.storeExists(request.storeId())) {
            throw new ProductionNotFoundException("Store", request.storeId());
        }
        Printer printer = save(Printer.create(request.storeId(), request.code(), request.name(), request.host(),
                request.port(), request.codePage(), request.profile(), clock.instant()));
        record("PRINTER_CREATED", printer, Map.of());
        return printer.toView();
    }

    @Transactional
    public PrinterView update(UUID id, PrinterUpdateRequest request) {
        Printer printer = authorized(id);
        OptimisticLocking.requireVersion(Printer.class, id, printer.getVersion(), request.version());
        printer.update(request.code(), request.name(), request.host(), request.port(), request.codePage(),
                request.profile(printer.profile()), request.active(), clock.instant());
        save(printer); // flush now: a concurrent writer fails here (409), and the response has the new version
        record("PRINTER_UPDATED", printer, Map.of("active", printer.isActive()));
        return printer.toView();
    }

    /**
     * Spec §8.6, D56. IGNORE also needs {@code PRODUCTION_SUPPRESS}. Both printers are locked in id order, so a
     * concurrent change cannot build a redirect chain. REDIRECT moves the undelivered (QUEUED, RETRY_WAIT, FAILED)
     * jobs to the target. Audited.
     */
    @Transactional
    public PrinterView changeMode(UUID id, PrinterModeRequest request) {
        UUID storeId = storeOf(id);
        accessPolicy.require(Permission.PRODUCTION_MANAGE, storeId);
        if (request.mode() == PrinterMode.IGNORE) {
            accessPolicy.require(Permission.PRODUCTION_SUPPRESS, storeId);
        }
        UUID targetId = request.redirectPrinterId();
        Map<UUID, Printer> locked = printers.lockAll(targetId == null ? List.of(id) : List.of(id, targetId))
                .stream().collect(Collectors.toMap(Printer::getId, Function.identity()));
        Printer printer = locked.get(id);
        OptimisticLocking.requireVersion(Printer.class, id, printer.getVersion(), request.version());
        Printer target = targetId == null ? null : locked.get(targetId);
        if (targetId != null && target == null) {
            throw Printer.invalidRedirect("Unknown redirect printer " + targetId);
        }
        if (request.mode() == PrinterMode.REDIRECT && printers.existsByRedirectPrinterId(id)) {
            throw Printer.invalidRedirect("Other printers redirect to " + printer.getCode() + ": no redirect chains");
        }
        PrinterMode from = printer.getMode();
        printer.changeMode(request.mode(), target, clock.instant());
        printers.saveAndFlush(printer);
        if (request.mode() != PrinterMode.NORMAL) {
            failQueuedKicks(printer);
        }
        int moved = target == null ? 0 : jobs.moveJobs(id, target.getId(),
                EnumSet.of(PrintJobStatus.QUEUED, PrintJobStatus.RETRY_WAIT, PrintJobStatus.FAILED));
        Map<String, Object> details = new HashMap<>();
        details.put("from", from.name());
        details.put("to", request.mode().name());
        details.put("movedJobs", moved);
        if (target != null) {
            details.put("redirectPrinterId", target.getId().toString());
        }
        record("PRINTER_MODE_CHANGED", printer, details);
        notifier.notifyAfterCommit(storeId, ChangeTopic.PRINT_JOBS, null);
        return printer.toView();
    }

    /**
     * D158 (amended 2026-11-13, S60 review): a printer leaving NORMAL fails its queued drawer kicks at once, before
     * a REDIRECT moves anything: a kick is never held or sent to another till's drawer. A worker claiming one
     * meanwhile makes this a version conflict (409).
     */
    private void failQueuedKicks(Printer printer) {
        for (PrintJob kick : jobs.findQueuedKicks(printer.getId())) {
            kick.failUnsent(clock.instant(), "PRINTER_NOT_NORMAL");
            jobs.saveAndFlush(kick);
        }
    }

    /** D42 (b): the printer's store only (404), then the permission (403), then the printer. */
    Printer authorized(UUID id) {
        accessPolicy.require(Permission.PRODUCTION_MANAGE, storeOf(id));
        return printers.findById(id).orElseThrow(() -> new ProductionNotFoundException(ENTITY, id));
    }

    private UUID storeOf(UUID id) {
        return probe.storeOf(PrinterRepository.TABLE, id)
                .orElseThrow(() -> new ProductionNotFoundException(ENTITY, id));
    }

    private Printer save(Printer printer) {
        return UniqueConstraints.saveAndFlush(printers, printer,
                () -> ProductionConflictException.printerCodeTaken(printer.getCode()), "printer_store_code_uk");
    }

    private void record(String action, Printer printer, Map<String, Object> extra) {
        Map<String, Object> details = new HashMap<>(extra);
        details.put("code", printer.getCode());
        auditLog.recordAsCurrentActor(action, ENTITY, printer.getId().toString(), printer.getStoreId(), null, null,
                details);
    }
}
