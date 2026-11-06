package com.xavier.backend.production.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.organization.OrganizationApi;
import com.xavier.backend.production.exception.ProductionConflictException;
import com.xavier.backend.production.exception.ProductionNotFoundException;
import com.xavier.backend.production.exception.ProductionRule;
import com.xavier.backend.production.exception.ProductionRuleViolationException;
import com.xavier.backend.production.model.DestinationRoute;
import com.xavier.backend.production.model.Printer;
import com.xavier.backend.production.model.TerminalPrinter;
import com.xavier.backend.production.model.dto.DestinationRouteView;
import com.xavier.backend.production.model.dto.RoutesRequest;
import com.xavier.backend.production.model.dto.RoutesView;
import com.xavier.backend.production.model.dto.TerminalPrinterView;
import com.xavier.backend.production.model.dto.TerminalPrintersRequest;
import com.xavier.backend.production.model.dto.TerminalPrintersView;
import com.xavier.backend.production.repository.DestinationRouteRepository;
import com.xavier.backend.production.repository.PrinterRepository;
import com.xavier.backend.production.repository.TerminalPrinterRepository;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Where work prints (D56): a store's production destinations and its terminals, each to a printer of that store.
 * BackOffice replaces a whole table at once ({@code PRODUCTION_MANAGE} in the store); tickets look a printer up.
 */
@Service
public class RoutingService {

    private final DestinationRouteRepository routes;
    private final TerminalPrinterRepository terminalPrinters;
    private final PrinterRepository printers;
    private final OrganizationApi organization;
    private final AccessPolicy accessPolicy;
    private final AuditLog auditLog;

    RoutingService(DestinationRouteRepository routes, TerminalPrinterRepository terminalPrinters,
                   PrinterRepository printers, OrganizationApi organization, AccessPolicy accessPolicy,
                   AuditLog auditLog) {
        this.routes = routes;
        this.terminalPrinters = terminalPrinters;
        this.printers = printers;
        this.organization = organization;
        this.accessPolicy = accessPolicy;
        this.auditLog = auditLog;
    }

    @Transactional(readOnly = true)
    public RoutesView routes(UUID storeId) {
        accessPolicy.require(Permission.PRODUCTION_MANAGE, storeId);
        return new RoutesView(storeId, routes.findByStore(storeId).stream().map(DestinationRoute::toView).toList());
    }

    @Transactional
    public RoutesView replaceRoutes(RoutesRequest request) {
        UUID storeId = request.storeId();
        requireStore(storeId);
        requireUnique(request.routes(), route -> route.destinationCode().strip(), "destination");
        requirePrintersOfStore(storeId, request.routes().stream().map(DestinationRouteView::printerId).toList());
        routes.deleteByStore(storeId);
        try {
            routes.saveAllAndFlush(request.routes().stream()
                    .map(route -> new DestinationRoute(storeId, route.destinationCode(), route.printerId())).toList());
        } catch (DataIntegrityViolationException e) {
            if (UniqueConstraints.isViolated(e, "destination_route_pk")) {
                throw ProductionConflictException.routesChangedConcurrently();
            }
            throw e;
        }
        auditLog.recordAsCurrentActor("PRODUCTION_ROUTES_REPLACED", "DestinationRoute", storeId.toString(), storeId,
                null, null, Map.of("routes", request.routes().size()));
        return new RoutesView(storeId, routes.findByStore(storeId).stream().map(DestinationRoute::toView).toList());
    }

    @Transactional(readOnly = true)
    public TerminalPrintersView terminalPrinters(UUID storeId) {
        accessPolicy.require(Permission.PRODUCTION_MANAGE, storeId);
        return terminalPrintersOf(storeId);
    }

    @Transactional
    public TerminalPrintersView replaceTerminalPrinters(TerminalPrintersRequest request) {
        UUID storeId = request.storeId();
        requireStore(storeId);
        requireUnique(request.assignments(), TerminalPrinterView::terminalId, "terminal");
        requirePrintersOfStore(storeId,
                request.assignments().stream().map(TerminalPrinterView::printerId).toList());
        terminalPrinters.deleteByStore(storeId);
        try {
            terminalPrinters.saveAllAndFlush(request.assignments().stream()
                    .map(a -> new TerminalPrinter(a.terminalId(), storeId, a.printerId())).toList());
        } catch (DataIntegrityViolationException e) {
            if (UniqueConstraints.isViolated(e, "terminal_printer_pk")) {
                throw ProductionConflictException.terminalAssignedElsewhere();
            }
            throw e;
        }
        auditLog.recordAsCurrentActor("TERMINAL_PRINTERS_REPLACED", "TerminalPrinter", storeId.toString(), storeId,
                null, null, Map.of("assignments", request.assignments().size()));
        return terminalPrintersOf(storeId);
    }

    /** The printer a destination of the store is routed to; empty when there is none (D56: not printed). */
    Optional<Printer> printerForDestination(UUID storeId, String destinationCode) {
        return routes.findById(new DestinationRoute.Key(storeId, destinationCode.strip()))
                .flatMap(route -> printers.findById(route.getPrinterId()));
    }

    /** Where the terminal's subtotals and documents print; empty for no terminal or no assignment. */
    Optional<Printer> printerForTerminal(UUID terminalId) {
        if (terminalId == null) {
            return Optional.empty();
        }
        return terminalPrinters.findById(terminalId).flatMap(t -> printers.findById(t.getPrinterId()));
    }

    private TerminalPrintersView terminalPrintersOf(UUID storeId) {
        return new TerminalPrintersView(storeId, terminalPrinters.findByStoreIdOrderByTerminalId(storeId).stream()
                .map(TerminalPrinter::toView).toList());
    }

    private void requireStore(UUID storeId) {
        accessPolicy.require(Permission.PRODUCTION_MANAGE, storeId);
        if (!organization.storeExists(storeId)) {
            throw new ProductionNotFoundException("Store", storeId);
        }
    }

    private static <T, K> void requireUnique(List<T> entries, Function<T, K> key, String what) {
        Set<K> seen = new HashSet<>();
        for (T entry : entries) {
            if (!seen.add(key.apply(entry))) {
                throw new ProductionRuleViolationException(ProductionRule.DUPLICATE_ROUTE,
                        "The " + what + " " + key.apply(entry) + " appears twice");
            }
        }
    }

    private void requirePrintersOfStore(UUID storeId, Collection<UUID> printerIds) {
        Set<UUID> wanted = new HashSet<>(printerIds);
        long ofStore = printers.findAllById(wanted).stream().filter(p -> p.getStoreId().equals(storeId)).count();
        if (ofStore != wanted.size()) {
            throw new ProductionRuleViolationException(ProductionRule.PRINTER_NOT_IN_STORE,
                    "Every printer must be a printer of store " + storeId);
        }
    }
}
