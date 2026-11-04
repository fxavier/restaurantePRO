package com.xavier.backend.identity.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.audit.AuditEntry;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.identity.exception.EmployeeAccountTakenException;
import com.xavier.backend.identity.exception.EmployeeNotFoundException;
import com.xavier.backend.identity.exception.RoleNotFoundException;
import com.xavier.backend.identity.exception.UserNotFoundException;
import com.xavier.backend.identity.exception.UsernameTakenException;
import com.xavier.backend.identity.model.UserAccount;
import com.xavier.backend.identity.model.dto.UserCreateRequest;
import com.xavier.backend.identity.model.dto.UserSummary;
import com.xavier.backend.identity.model.dto.UserUpdateRequest;
import com.xavier.backend.identity.repository.EmployeeRepository;
import com.xavier.backend.identity.repository.RoleRepository;
import com.xavier.backend.identity.repository.UserAccountRepository;
import com.xavier.backend.shared.web.PageResponse;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** BackOffice accounts (spec §5 "Acessos"). Installation-wide: every use case needs {@code USER_MANAGE}. */
@Service
public class UserAccountService {

    private final UserAccountRepository accounts;
    private final RoleRepository roles;
    private final EmployeeRepository employees;
    private final PasswordEncoder encoder;
    private final AccessPolicy accessPolicy;
    private final AuditLog auditLog;
    private final Clock clock;

    UserAccountService(UserAccountRepository accounts, RoleRepository roles, EmployeeRepository employees,
                       PasswordEncoder encoder, AccessPolicy accessPolicy, AuditLog auditLog, Clock clock) {
        this.accounts = accounts;
        this.roles = roles;
        this.employees = employees;
        this.encoder = encoder;
        this.accessPolicy = accessPolicy;
        this.auditLog = auditLog;
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
        UserAccount account = save(UserAccount.open(username, encoder.encode(request.initialPassword()),
                request.employeeId(), request.roleIds(), clock.instant()));
        audit("USER_CREATED", account);
        return account.toSummary();
    }

    @Transactional
    public UserSummary update(UUID id, UserUpdateRequest request) {
        accessPolicy.require(Permission.USER_MANAGE);
        UserAccount account = account(id);
        OptimisticLocking.requireVersion(UserAccount.class, id, account.getVersion(), request.version());
        requireLinks(request.employeeId(), request.roleIds());
        account.update(request.employeeId(), request.roleIds(), request.active(), clock.instant());
        save(account);
        audit("USER_UPDATED", account);
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
            if (UniqueConstraint.isViolated(e, "user_account_username_uk")) {
                throw new UsernameTakenException(account.getUsername());
            }
            if (UniqueConstraint.isViolated(e, "user_account_employee_uk")) {
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
        auditLog.record(new AuditEntry(accessPolicy.current().userId(), null, action, "UserAccount",
                account.getId().toString(), null, null, null, details));
    }
}
