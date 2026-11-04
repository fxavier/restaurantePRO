package com.xavier.backend.identity.service;

import com.xavier.backend.access.Permission;
import com.xavier.backend.identity.model.EmployeeStoreAssignment;
import com.xavier.backend.identity.model.Role;
import com.xavier.backend.identity.model.UserAccount;
import com.xavier.backend.identity.repository.EmployeeStoreAssignmentRepository;
import com.xavier.backend.identity.repository.RoleRepository;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Turns roles and store assignments into granted authorities in the {@link Permission} format (D27). These
 * become {@code Actor.authorities()}, the only input of {@code AccessPolicy}, and the session's granted
 * authorities. Resolved once at login: a role edited later applies from the next login.
 */
@Component
class AuthorityResolver {

    private final RoleRepository roles;
    private final EmployeeStoreAssignmentRepository assignments;

    AuthorityResolver(RoleRepository roles, EmployeeStoreAssignmentRepository assignments) {
        this.roles = roles;
        this.assignments = assignments;
    }

    /** BackOffice: installation-wide roles as {@code PERM_X}, plus every assigned store as {@code PERM_X@store}. */
    Set<String> ofAccount(UserAccount account) {
        Set<String> authorities = new HashSet<>();
        roles.findAllById(account.getRoleIds()).forEach(role ->
                role.getPermissions().forEach(permission -> authorities.add(permission.authority())));
        if (account.getEmployeeId() != null) {
            authorities.addAll(storeScoped(assignments.findByEmployeeId(account.getEmployeeId())));
        }
        return authorities;
    }

    /** POS: only the authorities of {@code storeId}, the terminal's store (D27 amended). */
    Set<String> ofEmployeeInStore(UUID employeeId, UUID storeId) {
        return storeScoped(assignments.findByEmployeeIdAndStoreId(employeeId, storeId));
    }

    private Set<String> storeScoped(List<EmployeeStoreAssignment> storeAssignments) {
        Map<UUID, Role> rolesById = rolesById(storeAssignments.stream().map(EmployeeStoreAssignment::getRoleId)
                .collect(Collectors.toSet()));
        Set<String> authorities = new HashSet<>();
        for (EmployeeStoreAssignment assignment : storeAssignments) {
            rolesById.get(assignment.getRoleId()).getPermissions()
                    .forEach(permission -> authorities.add(permission.authority(assignment.getStoreId())));
        }
        return authorities;
    }

    private Map<UUID, Role> rolesById(Collection<UUID> ids) {
        return roles.findAllById(ids).stream().collect(Collectors.toMap(Role::getId, Function.identity()));
    }
}
