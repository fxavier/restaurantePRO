package com.xavier.backend.production.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.production.exception.ProductionNotFoundException;
import com.xavier.backend.production.exception.ProductionRule;
import com.xavier.backend.production.exception.ProductionRuleViolationException;
import com.xavier.backend.production.model.KitchenProgress;
import com.xavier.backend.production.model.KitchenStatus;
import com.xavier.backend.production.model.ProductionTicket;
import com.xavier.backend.production.model.TicketSource;
import com.xavier.backend.production.model.dto.TicketView;
import com.xavier.backend.production.repository.KitchenProgressRepository;
import com.xavier.backend.production.repository.ProductionTicketRepository;
import com.xavier.backend.shared.notify.ChangeNotifier;
import com.xavier.backend.shared.notify.ChangeTopic;
import com.xavier.backend.shared.persistence.OptimisticLocking;
import com.xavier.backend.shared.persistence.StoreProbe;
import com.xavier.backend.shared.web.PageResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The kitchen display (ADR 0044): every round is queued on its station when production receives it, and a POS
 * session in the store lists its station's rounds and moves them along. Commands carry the version the screen shows
 * (stale → 409); each step is audited and announced on the change stream after commit. A ticket addressed by id:
 * 404, then 403 (D42).
 */
@Service
public class KitchenDisplayService {

    /** What one kitchen command does to a round: the employee and the time of the step. */
    @FunctionalInterface
    interface Step {
        void apply(KitchenProgress progress, UUID employee, Instant now);
    }

    private final KitchenProgressRepository progresses;
    private final ProductionTicketRepository tickets;
    private final AccessPolicy accessPolicy;
    private final StoreProbe probe;
    private final AuditLog auditLog;
    private final ChangeNotifier notifier;
    private final Clock clock;

    KitchenDisplayService(KitchenProgressRepository progresses, ProductionTicketRepository tickets,
                          AccessPolicy accessPolicy, StoreProbe probe, AuditLog auditLog, ChangeNotifier notifier,
                          Clock clock) {
        this.progresses = progresses;
        this.tickets = tickets;
        this.accessPolicy = accessPolicy;
        this.probe = probe;
        this.auditLog = auditLog;
        this.notifier = notifier;
        this.clock = clock;
    }

    /** A new round's ticket joins its station's queue, in production's transaction; any other ticket does not. */
    @Transactional(propagation = Propagation.MANDATORY)
    void queue(ProductionTicket ticket) {
        if (ticket.getSourceType() != TicketSource.BATCH) {
            return;
        }
        progresses.saveAndFlush(KitchenProgress.queue(ticket.getId(), ticket.getStoreId(), ticket.getDestination(),
                clock.instant()));
        notifier.notifyAfterCommit(ticket.getStoreId(), ChangeTopic.PRODUCTION_TICKETS, ticket.getId());
    }

    /**
     * The session store's rounds, oldest first.
     *
     * @param destination the station (a production destination code), or {@code null} for all
     * @param statuses    the statuses to show; empty for all
     */
    @Transactional(readOnly = true)
    public PageResponse<TicketView> listForPos(String destination, Set<KitchenStatus> statuses, Pageable pageable) {
        PosContext pos = accessPolicy.requirePos(Permission.PRODUCTION_VIEW);
        Set<KitchenStatus> shown = statuses == null || statuses.isEmpty() ? EnumSet.allOf(KitchenStatus.class)
                : statuses;
        Page<KitchenProgress> page = destination == null
                ? progresses.findByStoreIdAndStatusIn(pos.storeId(), shown, pageable)
                : progresses.findByStoreIdAndDestinationAndStatusIn(pos.storeId(), destination, shown, pageable);
        Map<UUID, ProductionTicket> byId = tickets.findAllById(page.map(KitchenProgress::getTicketId).toList())
                .stream().collect(Collectors.toMap(ProductionTicket::getId, Function.identity()));
        return PageResponse.from(page.map(progress -> byId.get(progress.getTicketId()).toView(progress)));
    }

    @Transactional
    public TicketView start(UUID ticketId, long version) {
        return move(ticketId, version, "STARTED", KitchenProgress::start);
    }

    @Transactional
    public TicketView ready(UUID ticketId, long version) {
        return move(ticketId, version, "READY", KitchenProgress::ready);
    }

    @Transactional
    public TicketView bump(UUID ticketId, long version) {
        return move(ticketId, version, "BUMPED", KitchenProgress::bump);
    }

    @Transactional
    public TicketView recall(UUID ticketId, long version) {
        return move(ticketId, version, "RECALLED", KitchenProgress::recall);
    }

    private TicketView move(UUID ticketId, long version, String step, Step command) {
        PosContext pos = accessPolicy.requirePos(Permission.PRODUCTION_OPERATE);
        accessPolicy.requireSessionStore(pos, ticketStore(ticketId), PrintJobService.TICKET, ticketId);
        KitchenProgress progress = progresses.findById(ticketId).orElseThrow(() ->
                new ProductionRuleViolationException(ProductionRule.NOT_KITCHEN_WORK,
                        "Production ticket " + ticketId + " is not a round: only rounds are prepared on the kitchen "
                                + "display"));
        OptimisticLocking.requireVersion(KitchenProgress.class, ticketId, progress.getVersion(), version);
        KitchenStatus from = progress.getStatus();
        command.apply(progress, pos.employeeId(), clock.instant());
        progresses.saveAndFlush(progress);
        auditLog.recordAsCurrentActor("PRODUCTION_TICKET_" + step, PrintJobService.TICKET, ticketId.toString(),
                progress.getStoreId(), null, null, Map.of("from", from.name(), "to", progress.getStatus().name(),
                        "destination", progress.getDestination()));
        notifier.notifyAfterCommit(progress.getStoreId(), ChangeTopic.PRODUCTION_TICKETS, ticketId);
        return tickets.findById(ticketId).orElseThrow().toView(progress);
    }

    private UUID ticketStore(UUID ticketId) {
        return probe.storeOf(ProductionTicketRepository.TABLE, ticketId)
                .orElseThrow(() -> new ProductionNotFoundException(PrintJobService.TICKET, ticketId));
    }
}
