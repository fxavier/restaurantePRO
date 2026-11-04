package com.xavier.backend.identity.model;

import com.xavier.backend.identity.model.dto.EmployeeSummary;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * An operational person who logs in on the POS with code + PIN (spec §3, §10, D26). Only the BCrypt hash of
 * the PIN is kept. {@value #MAX_FAILED_PIN_ATTEMPTS} consecutive wrong PINs lock the employee until a manager
 * unlocks them or gives them a new PIN.
 */
@Entity
@Table(schema = "identity", name = "employee")
public class Employee {

    public static final int MAX_FAILED_PIN_ATTEMPTS = 5;

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false, length = 20)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 100)
    private String pinHash;

    @Column(nullable = false)
    private int failedPinAttempts;

    @Column(nullable = false)
    private boolean locked;

    @Column(nullable = false)
    private boolean active;

    @Version
    private Long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Employee() {
        // JPA
    }

    public static Employee hire(String code, String name, String pinHash, Instant now) {
        Employee employee = new Employee();
        employee.code = code.strip();
        employee.name = name.strip();
        employee.pinHash = pinHash;
        employee.active = true;
        employee.createdAt = now;
        employee.updatedAt = now;
        return employee;
    }

    public boolean canLogIn() {
        return active && !locked;
    }

    /** @return {@code true} when this failure locked the employee */
    public boolean recordFailedPin(Instant now) {
        if (locked) {
            return false;
        }
        failedPinAttempts++;
        locked = failedPinAttempts >= MAX_FAILED_PIN_ATTEMPTS;
        updatedAt = now;
        return locked;
    }

    public void recordSuccessfulPin(Instant now) {
        if (failedPinAttempts == 0) {
            return;
        }
        failedPinAttempts = 0;
        updatedAt = now;
    }

    public void unlock(Instant now) {
        locked = false;
        failedPinAttempts = 0;
        updatedAt = now;
    }

    /** The failed attempts were against the old PIN, so a new PIN also unlocks. */
    public void resetPin(String newPinHash, Instant now) {
        pinHash = newPinHash;
        unlock(now);
    }

    public void update(String newName, boolean newActive, Instant now) {
        name = newName.strip();
        active = newActive;
        updatedAt = now;
    }

    public EmployeeSummary toSummary() {
        return new EmployeeSummary(id, code, name, active, locked, version == null ? 0 : version);
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getPinHash() {
        return pinHash;
    }

    public int getFailedPinAttempts() {
        return failedPinAttempts;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isLocked() {
        return locked;
    }

    public long getVersion() {
        return version == null ? 0 : version;
    }
}
