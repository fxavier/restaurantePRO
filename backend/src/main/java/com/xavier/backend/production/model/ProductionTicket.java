package com.xavier.backend.production.model;

import com.xavier.backend.production.model.dto.TicketView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;

/**
 * The logical ticket per source × destination (spec §4): a fact. Its text is fixed when it is created, so a copy or
 * a retry prints the same thing. {@code suppressed} means the printer was in IGNORE: there is no job, and the ticket
 * shows that the work was suppressed, not lost (§8.6). An UNROUTED ticket had no printer to go to (D89 (1)): it has
 * no printer and no job until it is routed, the one change a ticket takes (a compare-and-set in the repository, which
 * the table's trigger also allows only that way).
 */
@Entity
@Immutable
@Table(schema = "production", name = "production_ticket")
public class ProductionTicket {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false)
    private UUID storeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TicketSource sourceType;

    @Column(nullable = false)
    private UUID sourceId;

    @Column(nullable = false, length = 40)
    private String destination;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private JobKind kind;

    private UUID orderId;

    private UUID terminalId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TicketRouting routing;

    private UUID printerId;

    @Column(nullable = false)
    private boolean suppressed;

    @Column(nullable = false)
    private String content;

    private String qrPayload;

    @Column(nullable = false)
    private Instant createdAt;

    protected ProductionTicket() {
        // JPA
    }

    /**
     * @param printerId the printer the work was routed to (before a redirect)
     */
    public static ProductionTicket create(TicketDraft draft, UUID printerId, boolean suppressed, Instant now) {
        ProductionTicket ticket = of(draft, now);
        ticket.routing = TicketRouting.ROUTED;
        ticket.printerId = Objects.requireNonNull(printerId, "printerId");
        ticket.suppressed = suppressed;
        return ticket;
    }

    /** Work with no printer to go to (D89 (1)): kept, with no printer and no job, until it is routed. */
    public static ProductionTicket unrouted(TicketDraft draft, Instant now) {
        ProductionTicket ticket = of(draft, now);
        ticket.routing = TicketRouting.UNROUTED;
        return ticket;
    }

    private static ProductionTicket of(TicketDraft draft, Instant now) {
        ProductionTicket ticket = new ProductionTicket();
        ticket.storeId = draft.storeId();
        ticket.sourceType = draft.sourceType();
        ticket.sourceId = draft.sourceId();
        ticket.destination = draft.destination();
        ticket.kind = draft.kind();
        ticket.orderId = draft.orderId();
        ticket.terminalId = draft.terminalId();
        ticket.content = String.join("\n", draft.lines());
        ticket.qrPayload = draft.qrPayload();
        ticket.createdAt = now;
        return ticket;
    }

    public List<String> lines() {
        return List.of(content.split("\n", -1));
    }

    /** A COPY job prints the copy mark, except on a document: fiscal's copy printout carries its own (§8.4). */
    public boolean printsCopyMarkFor(JobKind jobKind) {
        return jobKind == JobKind.COPY && sourceType != TicketSource.DOCUMENT;
    }

    public TicketView toView() {
        return new TicketView(id, storeId, sourceType, sourceId, destination, kind, orderId, terminalId, printerId,
                suppressed, lines(), createdAt);
    }

    public UUID getId() {
        return id;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public TicketSource getSourceType() {
        return sourceType;
    }

    public UUID getSourceId() {
        return sourceId;
    }

    public String getDestination() {
        return destination;
    }

    public JobKind getKind() {
        return kind;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getTerminalId() {
        return terminalId;
    }

    public UUID getPrinterId() {
        return printerId;
    }

    public TicketRouting getRouting() {
        return routing;
    }

    public boolean isSuppressed() {
        return suppressed;
    }

    public String getQrPayload() {
        return qrPayload;
    }
}
