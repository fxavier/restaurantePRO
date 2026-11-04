package com.xavier.backend.identity.service;

import com.xavier.backend.audit.AuditEntry;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.identity.exception.WeakPasswordException;
import com.xavier.backend.identity.model.Role;
import com.xavier.backend.identity.model.UserAccount;
import com.xavier.backend.identity.repository.UserAccountRepository;
import com.xavier.backend.organization.model.event.CompanyBootstrapped;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first administrator when the company is bootstrapped (D30): installation-wide administrator role
 * (every permission, including {@code SYSTEM_MONITOR}) and {@code mustChangePassword}. Idempotent: an account with
 * that username already there means it is done, so a re-delivered event (restart) changes nothing; the unique
 * username constraint backs this up.
 */
@Service
@EnableConfigurationProperties(AdminBootstrapProperties.class)
public class AdminBootstrapService {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapService.class);

    private final UserAccountRepository accounts;
    private final PasswordEncoder encoder;
    private final AuditLog auditLog;
    private final Clock clock;
    private final AdminBootstrapProperties settings;

    AdminBootstrapService(UserAccountRepository accounts, PasswordEncoder encoder, AuditLog auditLog, Clock clock,
                          AdminBootstrapProperties settings) {
        this.accounts = accounts;
        this.encoder = encoder;
        this.auditLog = auditLog;
        this.clock = clock;
        this.settings = settings;
    }

    @ApplicationModuleListener
    void on(CompanyBootstrapped event) {
        onCompanyBootstrapped(settings);
    }

    /**
     * {@code CompanyBootstrapped} is published once per installation. Whenever the admin account does not exist
     * afterwards (settings missing, password file unreadable, password too weak), this fails after the WARN, so
     * the publication stays outstanding and the next start with fixed settings creates the admin (republish on
     * restart, D19). From the listener it runs in the listener's transaction; {@code @Transactional} only matters
     * when it is called directly through the bean (tests).
     */
    @Transactional
    void onCompanyBootstrapped(AdminBootstrapProperties configured) {
        createAdmin(configured);
        if (!configured.isComplete() || !accounts.existsByUsername(UserAccount.normalize(configured.username()))) {
            throw new IllegalStateException("No administrator yet: fix restaurantpro.bootstrap.admin.* "
                    + "(see the WARN above) and restart");
        }
    }

    /** @return {@code true} only when this call created the admin */
    @Transactional
    public boolean createAdmin(AdminBootstrapProperties configured) {
        if (!configured.isComplete()) {
            log.warn("No administrator created: set RESTAURANTPRO_BOOTSTRAP_ADMIN_USERNAME and "
                    + "RESTAURANTPRO_BOOTSTRAP_ADMIN_INITIAL_PASSWORD_FILE and restart");
            return false;
        }
        String username = UserAccount.normalize(configured.username());
        if (accounts.existsByUsername(username)) {
            return false;
        }
        Optional<String> password = initialPassword(configured);
        if (password.isEmpty()) {
            return false;
        }
        UserAccount admin;
        try {
            admin = accounts.saveAndFlush(UserAccount.open(username, encoder.encode(password.get()), null,
                    Set.of(Role.ADMINISTRATOR_ID), clock.instant()));
        } catch (DataIntegrityViolationException e) {
            if (UniqueConstraints.isViolated(e, "user_account_username_uk")) {
                return false; // created concurrently by another delivery
            }
            throw e;
        }
        auditLog.record(new AuditEntry(null, null, "USER_CREATED", "UserAccount", admin.getId().toString(), null,
                null, "Administrator bootstrap", Map.of("username", username, "roleIds", admin.getRoleIds())));
        log.info("Administrator '{}' created; the initial password must be changed at first login", username);
        return true;
    }

    private static Optional<String> initialPassword(AdminBootstrapProperties configured) {
        String password;
        try {
            password = Files.readString(configured.initialPasswordFile()).replaceAll("[\\r\\n]+$", "");
        } catch (IOException e) {
            log.warn("No administrator created: cannot read the initial password file {} "
                    + "(RESTAURANTPRO_BOOTSTRAP_ADMIN_INITIAL_PASSWORD_FILE)", configured.initialPasswordFile());
            return Optional.empty();
        }
        try {
            PasswordPolicy.requireStrong(password);
        } catch (WeakPasswordException e) {
            log.warn("No administrator created: the initial password is too weak ({})", e.getMessage());
            return Optional.empty();
        }
        return Optional.of(password);
    }
}
