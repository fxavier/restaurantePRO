package com.xavier.backend.organization.model;

import com.xavier.backend.organization.exception.IllegalTerminalStateException;
import com.xavier.backend.organization.exception.InvalidEnrollmentCodeException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * A registered POS station of a store (spec §8.2, D26). Enrolled once with a one-time code, then identified
 * by its device token. Only SHA-256 hashes of the code and token are kept, never the plaintext.
 */
@Entity
@Table(schema = "organization", name = "terminal")
public class Terminal {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID storeId;

    @Column(nullable = false, length = 20)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TerminalStatus status;

    @Column(length = 64)
    private String enrollmentCodeHash;

    private Instant enrollmentCodeExpiresAt;

    @Column(length = 64)
    private String deviceTokenHash;

    @Version
    private Long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Terminal() {
        // JPA
    }

    public static Terminal register(UUID storeId, String code, String name, Instant now) {
        Terminal terminal = new Terminal();
        terminal.storeId = storeId;
        terminal.code = code.strip();
        terminal.name = name.strip();
        terminal.status = TerminalStatus.REGISTERED;
        terminal.createdAt = now;
        terminal.updatedAt = now;
        return terminal;
    }

    /** Stores a new one-time code (replacing any previous one). Only a terminal not yet enrolled gets one. */
    public void issueEnrollmentCode(String codeHash, Instant expiresAt, Instant now) {
        if (status != TerminalStatus.REGISTERED) {
            throw new IllegalTerminalStateException("Only a terminal that is not enrolled yet can get a code; this one is "
                    + status);
        }
        this.enrollmentCodeHash = codeHash;
        this.enrollmentCodeExpiresAt = expiresAt;
        this.updatedAt = now;
    }

    /**
     * Exchanges the one-time code for a device token: the code must match, must not have expired
     * ({@code now} before the expiry instant) and is consumed, so it never works twice.
     */
    public void enroll(String presentedCodeHash, String deviceTokenHash, Instant now) {
        if (status != TerminalStatus.REGISTERED || enrollmentCodeHash == null
                || !enrollmentCodeHash.equals(presentedCodeHash) || !now.isBefore(enrollmentCodeExpiresAt)) {
            throw new InvalidEnrollmentCodeException();
        }
        this.status = TerminalStatus.ACTIVE;
        this.enrollmentCodeHash = null;
        this.enrollmentCodeExpiresAt = null;
        this.deviceTokenHash = deviceTokenHash;
        this.updatedAt = now;
    }

    public void revoke(Instant now) {
        if (status == TerminalStatus.REVOKED) {
            throw new IllegalTerminalStateException("The terminal is already revoked");
        }
        this.status = TerminalStatus.REVOKED;
        this.enrollmentCodeHash = null;
        this.enrollmentCodeExpiresAt = null;
        this.updatedAt = now;
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

    public String getName() {
        return name;
    }

    public TerminalStatus getStatus() {
        return status;
    }

    public String getDeviceTokenHash() {
        return deviceTokenHash;
    }

    public Instant getEnrollmentCodeExpiresAt() {
        return enrollmentCodeExpiresAt;
    }

    public Long getVersion() {
        return version;
    }
}
