package com.xavier.backend.identity.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.identity.exception.EmployeeAccountTakenException;
import com.xavier.backend.identity.exception.EmployeeNotFoundException;
import com.xavier.backend.identity.exception.LastAdministratorException;
import com.xavier.backend.identity.exception.RoleNotFoundException;
import com.xavier.backend.identity.exception.SupportAccountLinkedException;
import com.xavier.backend.identity.exception.UserNotFoundException;
import com.xavier.backend.identity.exception.UsernameTakenException;
import com.xavier.backend.identity.model.Role;
import com.xavier.backend.identity.model.UserAccount;
import com.xavier.backend.identity.model.dto.UserCreateRequest;
import com.xavier.backend.identity.model.dto.UserSummary;
import com.xavier.backend.identity.model.dto.UserUpdateRequest;
import com.xavier.backend.identity.repository.EmployeeRepository;
import com.xavier.backend.identity.repository.RoleRepository;
import com.xavier.backend.identity.repository.UserAccountRepository;
import com.xavier.backend.shared.persistence.OptimisticLocking;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import com.xavier.backend.shared.web.PageResponse;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * BackOffice accounts (spec §5 "Acessos"). Installation-wide: every use case needs {@code USER_MANAGE}; granting a
 * role or linking an employee also needs what is being handed out.
 */
@Service
public class UserAccountService {

    private final UserAccountRepository accounts;
    private final RoleRepository roles;
    private final EmployeeRepository employees;
    private final PasswordEncoder encoder;
    private final AccessPolicy accessPolicy;
    private final AuditLog auditLog;
    private final SessionTermination sessions;
    private final EmployeeControl employeeControl;
    private final Clock clock;

    UserAccountService(UserAccountRepository accounts, RoleRepository roles, EmployeeRepository employees,
                       PasswordEncoder encoder, AccessPolicy accessPolicy, AuditLog auditLog,
                       SessionTermination sessions, EmployeeControl employeeControl, Clock clock) {
        this.accounts = accounts;
        this.roles = roles;
        this.employees = employees;
        this.encoder = encoder;
        this.accessPolicy = accessPolicy;
        this.auditLog = auditLog;
        this.sessions = sessions;
        this.employeeControl = employeeControl;
        this.clock = clock;
    }

    @Transactional
    public UserSummary create(UserCreateRequest request) {
        accessPolicy.require(Permission.USER_MANAGE);
        PasswordPolicy.requireStrong(request.initialPassword());
        String username = UserAccount.normalize(request.username());
        if (accounts.existsByUsername(username)) {
            throw new UsernameTakenException(username);
        }
        requireLinks(request.employeeId(), request.roleIds());
        requireCanGrant(request.roleIds());
        requireCanLink(null, request.employeeId());
        String hash = encoder.encode(request.initialPassword());
        UserAccount account = save(request.support()
                ? supportAccount(username, hash, request)
                : UserAccount.open(username, hash, request.employeeId(), request.roleIds(), clock.instant()));
        audit("USER_CREATED", account);
        return account.toSummary();
    }

    @Transactional
    public UserSummary update(UUID id, UserUpdateRequest request) {
        accessPolicy.require(Permission.USER_MANAGE);
        UserAccount account = account(id);
        OptimisticLocking.requireVersion(UserAccount.class, id, account.getVersion(), request.version());
        requireLinks(request.employeeId(), request.roleIds());
        requireCanGrant(request.roleIds().stream().filter(role -> !account.getRoleIds().contains(role))
                .collect(Collectors.toSet()));
        requireCanLink(account.getEmployeeId(), request.employeeId());
        boolean wasAdministrator = isActiveAdministrator(account);
        boolean authoritiesMayShrink = !request.active() || !account.getRoleIds().equals(request.roleIds())
                || !Objects.equals(account.getEmployeeId(), request.employeeId());
        account.update(request.employeeId(), request.roleIds(), request.active(), clock.instant());
        if (wasAdministrator && !isActiveAdministrator(account)) {
            requireAnotherActiveAdministrator(account);
        }
        save(account);
        audit("USER_UPDATED", account);
        if (authoritiesMayShrink) {
            sessions.endAccountSessions(account.getId()); // its sessions hold the authorities resolved at login
        }
        return account.toSummary();
    }

    @Transactional(readOnly = true)
    public UserSummary get(UUID id) {
        accessPolicy.require(Permission.USER_MANAGE);
        return account(id).toSummary();
    }

    @Transactional(readOnly = true)
    public PageResponse<UserSummary> list(Pageable pageable) {
        accessPolicy.require(Permission.USER_MANAGE);
        return PageResponse.from(accounts.findAll(pageable).map(UserAccount::toSummary));
    }

    /** Support accounts are an administrator's matter (D83): creating one needs {@code SUPPORT_ACCESS_MANAGE}. */
    private UserAccount supportAccount(String username, String hash, UserCreateRequest request) {
        accessPolicy.require(Permission.SUPPORT_ACCESS_MANAGE);
        if (request.employeeId() != null) {
            throw new SupportAccountLinkedException();
        }
        return UserAccount.openSupport(username, hash, request.roleIds(), clock.instant());
    }

    /** Nobody gives an account a role with a permission they do not hold installation-wide themselves. */
    private void requireCanGrant(Set<UUID> roleIds) {
        roles.findAllById(roleIds).forEach(role -> role.getPermissions().forEach(accessPolicy::require));
    }

    /**
     * A linked account holds the employee's store authorities, so a new link needs what controlling the employee
     * needs ({@link EmployeeControl}). Removing or keeping a link grants nothing.
     */
    private void requireCanLink(UUID currentEmployeeId, UUID newEmployeeId) {
        if (newEmployeeId != null && !newEmployeeId.equals(currentEmployeeId)) {
            employeeControl.require(newEmployeeId);
        }
    }

    /** A support account is never the installation's administrator: it cannot manage users or roles (D83). */
    private static boolean isActiveAdministrator(UserAccount account) {
        return account.isActive() && !account.isSupport() && account.getRoleIds().contains(Role.ADMINISTRATOR_ID);
    }

    /**
     * Locks the administrator role row first, so two concurrent demotions run this check one after the other and
     * the second sees the first one's committed result.
     */
    private void requireAnotherActiveAdministrator(UserAccount account) {
        roles.lockRow(Role.ADMINISTRATOR_ID);
        if (accounts.countActiveNonSupportHoldersExcept(Role.ADMINISTRATOR_ID, account.getId()) == 0) {
            throw new LastAdministratorException();
        }
    }

    private void requireLinks(UUID employeeId, Set<UUID> roleIds) {
        if (employeeId != null && !employees.existsById(employeeId)) {
            throw new EmployeeNotFoundException(employeeId);
        }
        roleIds.stream().filter(roleId -> !roles.existsById(roleId)).findFirst().ifPresent(roleId -> {
            throw new RoleNotFoundException(roleId);
        });
    }

    private UserAccount save(UserAccount account) {
        try {
            return accounts.saveAndFlush(account);
        } catch (DataIntegrityViolationException e) {
            if (UniqueConstraints.isViolated(e, "user_account_username_uk")) {
                throw new UsernameTakenException(account.getUsername());
            }
            if (UniqueConstraints.isViolated(e, "user_account_employee_uk")) {
                throw new EmployeeAccountTakenException(account.getEmployeeId());
            }
            throw e;
        }
    }

    private UserAccount account(UUID id) {
        return accounts.findById(id).orElseThrow(() -> new UserNotFoundException(id));
    }

    private void audit(String action, UserAccount account) {
        Map<String, Object> details = new HashMap<>();
        details.put("username", account.getUsername());
        details.put("employeeId", account.getEmployeeId());
        details.put("roleIds", account.getRoleIds());
        details.put("active", account.isActive());
        details.put("support", account.isSupport());
        auditLog.recordAsCurrentActor(action, "UserAccount", account.getId().toString(), null, null, null, details);
    }
}
