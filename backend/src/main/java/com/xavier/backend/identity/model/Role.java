package com.xavier.backend.identity.model;

import com.xavier.backend.access.Permission;
import com.xavier.backend.identity.exception.ReadOnlyRoleException;
import com.xavier.backend.identity.model.dto.RoleSummary;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/** A named, editable set of {@link Permission}s (D27). Seven are seeded from spec §3 by Flyway. */
@Entity
@Table(schema = "identity", name = "role")
public class Role {

    /** The seeded "Administrador" role: every permission, read-only through the API. */
    public static final UUID ADMINISTRATOR_ID = UUID.fromString("01900000-0000-7000-8000-000000000301");

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, length = 60)
    private String name;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(schema = "identity", name = "role_permission", joinColumns = @JoinColumn(name = "role_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "permission", nullable = false, length = 40)
    private Set<Permission> permissions = new HashSet<>();

    @Version
    private Long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Role() {
        // JPA
    }

    public static Role define(String name, Set<Permission> permissions, Instant now) {
        Role role = new Role();
        role.name = name.strip();
        role.permissions.addAll(permissions);
        role.createdAt = now;
        role.updatedAt = now;
        return role;
    }

    public void redefine(String newName, Set<Permission> newPermissions, Instant now) {
        if (isAdministrator()) {
            throw new ReadOnlyRoleException();
        }
        name = newName.strip();
        permissions.clear();
        permissions.addAll(newPermissions);
        updatedAt = now;
    }

    public boolean isAdministrator() {
        return ADMINISTRATOR_ID.equals(id);
    }

    public RoleSummary toSummary() {
        return new RoleSummary(id, name, getPermissions(), getVersion());
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Set<Permission> getPermissions() {
        return permissions.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(permissions));
    }

    public long getVersion() {
        return version == null ? 0 : version;
    }
}
