package com.xavier.backend.identity.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.identity.exception.RoleNameTakenException;
import com.xavier.backend.identity.exception.RoleNotFoundException;
import com.xavier.backend.identity.model.Role;
import com.xavier.backend.identity.model.dto.RoleCreateRequest;
import com.xavier.backend.identity.model.dto.RoleSummary;
import com.xavier.backend.identity.model.dto.RoleUpdateRequest;
import com.xavier.backend.identity.repository.RoleRepository;
import com.xavier.backend.shared.persistence.OptimisticLocking;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import com.xavier.backend.shared.web.PageResponse;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roles (D27): installation-wide data, every use case needs {@code ROLE_MANAGE} installation-wide, and nobody puts
 * into a role a permission they do not hold installation-wide. No delete:
 * assignments and the audit trail refer to roles; an unused role can simply be left unassigned.
 */
@Service
public class RoleService {

    private final RoleRepository roles;
    private final AccessPolicy accessPolicy;
    private final AuditLog auditLog;
    private final SessionTermination sessions;
    private final Clock clock;

    RoleService(RoleRepository roles, AccessPolicy accessPolicy, AuditLog auditLog, SessionTermination sessions,
                Clock clock) {
        this.roles = roles;
        this.accessPolicy = accessPolicy;
        this.auditLog = auditLog;
        this.sessions = sessions;
        this.clock = clock;
    }

    @Transactional
    public RoleSummary create(RoleCreateRequest request) {
        accessPolicy.require(Permission.ROLE_MANAGE);
        if (roles.existsByName(request.name().strip())) {
            throw new RoleNameTakenException(request.name().strip());
        }
        request.permissions().forEach(accessPolicy::require);
        Role role = save(Role.define(request.name(), request.permissions(), clock.instant()));
        audit("ROLE_CREATED", role);
        return role.toSummary();
    }

    @Transactional
    public RoleSummary update(UUID id, RoleUpdateRequest request) {
        accessPolicy.require(Permission.ROLE_MANAGE);
        Role role = role(id);
        OptimisticLocking.requireVersion(Role.class, id, role.getVersion(), request.version());
        request.permissions().stream().filter(permission -> !role.getPermissions().contains(permission))
                .forEach(accessPolicy::require);
        boolean permissionsShrink = !request.permissions().containsAll(role.getPermissions());
        role.redefine(request.name(), request.permissions(), clock.instant());
        save(role);
        audit("ROLE_UPDATED", role);
        if (permissionsShrink) {
            sessions.endRoleSessions(id); // they hold the permissions resolved at login
        }
        return role.toSummary();
    }

    @Transactional(readOnly = true)
    public RoleSummary get(UUID id) {
        accessPolicy.require(Permission.ROLE_MANAGE);
        return role(id).toSummary();
    }

    @Transactional(readOnly = true)
    public PageResponse<RoleSummary> list(Pageable pageable) {
        accessPolicy.require(Permission.ROLE_MANAGE);
        return PageResponse.from(roles.findAll(pageable).map(Role::toSummary));
    }

    private Role role(UUID id) {
        return roles.findById(id).orElseThrow(() -> new RoleNotFoundException(id));
    }

    private Role save(Role role) {
        return UniqueConstraints.saveAndFlush(roles, role,
                () -> new RoleNameTakenException(role.getName()), "role_name_uk");
    }

    private void audit(String action, Role role) {
        auditLog.recordAsCurrentActor(action, "Role", role.getId().toString(), null, null, null,
                Map.of("name", role.getName(),
                        "permissions", role.getPermissions().stream().map(Enum::name).sorted().toList()));
    }
}
