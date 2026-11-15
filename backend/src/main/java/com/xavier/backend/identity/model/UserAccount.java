package com.xavier.backend.identity.model;

import com.xavier.backend.identity.exception.SupportAccountLinkedException;
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
 * A BackOffice account of the tenant (D175): its profile, roles and status. Keycloak owns the credentials, the second
 * factor and the sessions; an {@code ACCOUNT} membership links a Keycloak user to this account (D177). Store
 * permissions come from the linked employee's store assignments; {@code roleIds} are tenant-wide roles (every store).
 * A {@code support} account (D83, D183) acts only through an active {@link SupportGrant}'s {@code SUPPORT}
 * membership; the flag is fixed at creation, and such an account is never linked to an employee.
 */
@Entity
@Table(schema = "identity", name = "user_account")
public class UserAccount {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false, length = 254)
    private String username;

    @Column(nullable = false)
    private boolean active;

    private UUID employeeId;

    @Column(nullable = false, updatable = false)
    private boolean support;

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

    /** A new account; it acts once a Keycloak user holds its {@code ACCOUNT} membership. */
    public static UserAccount open(String username, UUID employeeId, Set<UUID> roleIds, Instant now) {
        UserAccount account = new UserAccount();
        account.username = normalize(username);
        account.active = true;
        account.employeeId = employeeId;
        account.roleIds.addAll(roleIds);
        account.createdAt = now;
        account.updatedAt = now;
        return account;
    }

    /** A new support account (D83): no employee link; it acts only while a grant is active. */
    public static UserAccount openSupport(String username, Set<UUID> roleIds, Instant now) {
        UserAccount account = open(username, null, roleIds, now);
        account.support = true;
        return account;
    }

    public static String normalize(String username) {
        return username.strip().toLowerCase(Locale.ROOT);
    }

    public void update(UUID newEmployeeId, Set<UUID> newRoleIds, boolean newActive, Instant now) {
        if (support && newEmployeeId != null) {
            throw new SupportAccountLinkedException();
        }
        employeeId = newEmployeeId;
        roleIds.clear();
        roleIds.addAll(newRoleIds);
        active = newActive;
        updatedAt = now;
    }

    public UserSummary toSummary() {
        return new UserSummary(id, username, employeeId, Set.copyOf(roleIds), active, support, getVersion());
    }

    public UUID getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isSupport() {
        return support;
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
