package com.xavier.backend.production.model;

import com.xavier.backend.production.exception.ProductionRule;
import com.xavier.backend.production.exception.ProductionRuleViolationException;
import com.xavier.backend.production.model.dto.PrinterProfileView;
import com.xavier.backend.production.model.dto.PrinterView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * A network receipt printer of a store (spec §4, §8.6). Never deleted: tickets and jobs refer to it, so it is
 * deactivated instead (an inactive printer's jobs wait). The mode decides what happens to new work.
 */
@Entity
@Table(schema = "production", name = "printer")
public class Printer {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID storeId;

    @Column(nullable = false, length = 20)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false)
    private String host;

    @Column(nullable = false)
    private int port;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CodePage codePage;

    @Column(nullable = false)
    private boolean active;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PrinterMode mode;

    private UUID redirectPrinterId;

    @Column(nullable = false)
    private short paperWidthMm;

    @Column(nullable = false)
    private short columns;

    private Short codeTable;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private CutMode cutMode;

    @Column(nullable = false)
    private short feedLines;

    @Column(nullable = false)
    private boolean statusProbe;

    private Short drawerPin;

    @Column(nullable = false)
    private short drawerPulseMs;

    /** Written by the print worker with a query of its own, never through this entity (it would move the version). */
    @Column(insertable = false, updatable = false, length = 20)
    private String lastStatus;

    @Column(insertable = false, updatable = false)
    private Instant lastStatusAt;

    @Version
    private Long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Printer() {
        // JPA
    }

    public static Printer create(UUID storeId, String code, String name, String host, int port, CodePage codePage,
                                 PrinterProfile profile, Instant now) {
        Printer printer = new Printer();
        printer.storeId = Objects.requireNonNull(storeId, "storeId");
        printer.mode = PrinterMode.NORMAL;
        printer.createdAt = now;
        printer.update(code, name, host, port, codePage, profile, true, now);
        return printer;
    }

    public void update(String code, String name, String host, int port, CodePage codePage, PrinterProfile profile,
                       boolean active, Instant now) {
        this.code = code.strip();
        this.name = name.strip();
        this.host = host.strip();
        this.port = port;
        this.codePage = Objects.requireNonNull(codePage, "codePage");
        this.active = active;
        this.paperWidthMm = (short) profile.paperWidthMm();
        this.columns = (short) profile.columns();
        this.codeTable = profile.codeTable() == null ? null : profile.codeTable().shortValue();
        this.cutMode = profile.cutMode();
        this.feedLines = (short) profile.feedLines();
        this.statusProbe = profile.statusProbe();
        this.drawerPin = profile.drawerPin() == null ? null : profile.drawerPin().shortValue();
        this.drawerPulseMs = (short) profile.drawerPulseMs();
        this.updatedAt = now;
    }

    /** How this printer lays out and ends a ticket (D154). */
    public PrinterProfile profile() {
        return new PrinterProfile(paperWidthMm, columns, codeTable == null ? null : codeTable.intValue(), cutMode,
                feedLines, statusProbe, drawerPin == null ? null : drawerPin.intValue(), drawerPulseMs);
    }

    /**
     * @param redirectTarget the printer REDIRECT sends to; {@code null} for every other mode. Whether some other
     *                       printer redirects to this one (a chain) is the caller's check: it needs the store's
     *                       printers.
     */
    public void changeMode(PrinterMode newMode, Printer redirectTarget, Instant now) {
        Objects.requireNonNull(newMode, "mode");
        if (newMode == PrinterMode.REDIRECT) {
            requireRedirectTarget(redirectTarget);
        } else if (redirectTarget != null) {
            throw invalidRedirect("Only REDIRECT names a redirect printer");
        }
        mode = newMode;
        redirectPrinterId = redirectTarget == null ? null : redirectTarget.id;
        updatedAt = now;
    }

    private void requireRedirectTarget(Printer target) {
        if (target == null) {
            throw invalidRedirect("REDIRECT needs a redirect printer");
        }
        if (target.id.equals(id)) {
            throw invalidRedirect("A printer cannot redirect to itself");
        }
        if (!target.storeId.equals(storeId)) {
            throw invalidRedirect("The redirect printer must be in the same store");
        }
        if (target.mode == PrinterMode.REDIRECT) {
            throw invalidRedirect("Printer " + target.code + " redirects itself: no redirect chains");
        }
    }

    public static ProductionRuleViolationException invalidRedirect(String detail) {
        return new ProductionRuleViolationException(ProductionRule.PRINTER_REDIRECT_INVALID, detail);
    }

    public PrinterAddress address() {
        return new PrinterAddress(host, port);
    }

    public PrinterView toView() {
        return new PrinterView(id, storeId, code, name, host, port, codePage, active, mode, redirectPrinterId,
                version, PrinterProfileView.from(profile()), lastStatus, lastStatusAt);
    }

    public UUID getId() {
        return id;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public String getCode() {
        return code;
    }

    public CodePage getCodePage() {
        return codePage;
    }

    public boolean isActive() {
        return active;
    }

    public PrinterMode getMode() {
        return mode;
    }

    public UUID getRedirectPrinterId() {
        return redirectPrinterId;
    }

    public Long getVersion() {
        return version;
    }
}
