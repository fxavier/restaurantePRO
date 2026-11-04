package com.xavier.backend.identity.model;

import com.xavier.backend.identity.model.dto.UserSummary;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * A BackOffice login: username + password (D26). Only the password hash is kept. Store permissions come from
 * the linked employee's store assignments; {@code roleIds} are installation-wide roles (every store). A new
 * account must change its password at first login.
 */
@Entity
@Table(schema = "identity", name = "user_account")
public class UserAccount {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false, length = 60)
    private String username;

    @Column(nullable = false, length = 100)
    private String passwordHash;

    @Column(nullable = false)
    private boolean mustChangePassword;

    @Column(nullable = false)
    private boolean active;

    private UUID employeeId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(schema = "identity", name = "user_account_role",
            joinColumns = @JoinColumn(name = "user_account_id"))
    @Column(name = "role_id", nullable = false)
    private Set<UUID> roleIds = new HashSet<>();

    @Version
    private Long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected UserAccount() {
        // JPA
    }

    /** A new account with an initial password that must be changed at first login. */
    public static UserAccount open(String username, String initialPasswordHash, UUID employeeId, Set<UUID> roleIds,
                                   Instant now) {
        UserAccount account = new UserAccount();
        account.username = normalize(username);
        account.passwordHash = initialPasswordHash;
        account.mustChangePassword = true;
        account.active = true;
        account.employeeId = employeeId;
        account.roleIds.addAll(roleIds);
        account.createdAt = now;
        account.updatedAt = now;
        return account;
    }

    public static String normalize(String username) {
        return username.strip().toLowerCase(Locale.ROOT);
    }

    public void changePassword(String newPasswordHash, Instant now) {
        passwordHash = newPasswordHash;
        mustChangePassword = false;
        updatedAt = now;
    }

    public void update(UUID newEmployeeId, Set<UUID> newRoleIds, boolean newActive, Instant now) {
        employeeId = newEmployeeId;
        roleIds.clear();
        roleIds.addAll(newRoleIds);
        active = newActive;
        updatedAt = now;
    }

    public UserSummary toSummary() {
        return new UserSummary(id, username, employeeId, Set.copyOf(roleIds), active, mustChangePassword,
                getVersion());
    }

    public UUID getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    public boolean isActive() {
        return active;
    }

    public UUID getEmployeeId() {
        return employeeId;
    }

    public Set<UUID> getRoleIds() {
        return Set.copyOf(roleIds);
    }

    public long getVersion() {
        return version == null ? 0 : version;
    }
}
