package com.xavier.backend.identity.model;

import com.xavier.backend.identity.model.dto.AssignmentSummary;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/** Employee × Store × Role (D27): the employee holds the role's permissions in that store only. Immutable. */
@Entity
@Table(schema = "identity", name = "employee_store_assignment")
public class EmployeeStoreAssignment {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID employeeId;

    @Column(nullable = false, updatable = false)
    private UUID storeId;

    @Column(nullable = false, updatable = false)
    private UUID roleId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected EmployeeStoreAssignment() {
        // JPA
    }

    public EmployeeStoreAssignment(UUID employeeId, UUID storeId, UUID roleId, Instant now) {
        this.employeeId = employeeId;
        this.storeId = storeId;
        this.roleId = roleId;
        this.createdAt = now;
    }

    public AssignmentSummary toSummary() {
        return new AssignmentSummary(id, employeeId, storeId, roleId);
    }

    public UUID getId() {
        return id;
    }

    public UUID getEmployeeId() {
        return employeeId;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public UUID getRoleId() {
        return roleId;
    }
}
