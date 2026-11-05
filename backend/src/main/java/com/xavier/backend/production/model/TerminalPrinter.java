package com.xavier.backend.production.model;

import com.xavier.backend.production.model.dto.TerminalPrinterView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * Where a terminal's subtotals and documents print (D56). A new assignment is always inserted (never merged into
 * a row of another store): a terminal assigned elsewhere violates the primary key instead of moving silently.
 */
@Entity
@Table(schema = "production", name = "terminal_printer")
public class TerminalPrinter implements Persistable<UUID> {

    @Id
    private UUID terminalId;

    @Column(nullable = false)
    private UUID storeId;

    @Column(nullable = false)
    private UUID printerId;

    @Transient
    private boolean isNew = true;

    protected TerminalPrinter() {
        // JPA
    }

    public TerminalPrinter(UUID terminalId, UUID storeId, UUID printerId) {
        this.terminalId = Objects.requireNonNull(terminalId, "terminalId");
        this.storeId = Objects.requireNonNull(storeId, "storeId");
        this.printerId = Objects.requireNonNull(printerId, "printerId");
    }

    @Override
    public UUID getId() {
        return terminalId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    void loaded() {
        isNew = false;
    }

    public UUID getPrinterId() {
        return printerId;
    }

    public TerminalPrinterView toView() {
        return new TerminalPrinterView(terminalId, printerId);
    }
}
