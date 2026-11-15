package com.xavier.backend.identity.service;

import com.xavier.backend.identity.model.Role;
import com.xavier.backend.identity.model.UserAccount;
import com.xavier.backend.identity.repository.RoleRepository;
import com.xavier.backend.identity.repository.UserAccountRepository;
import com.xavier.backend.organization.model.event.CompanyProvisioned;
import com.xavier.backend.shared.exception.UnavailableException;
import com.xavier.backend.shared.tenancy.MembershipKind;
import com.xavier.backend.tenancy.TenancyApi;
import com.xavier.backend.tenancy.model.dto.MembershipGrant;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The last link of onboarding (D181 as amended): {@code CompanyProvisioned} → in the event's tenant, the tenant's
 * administrator role (every permission) and the local administrator account (its e-mail as the username, all stores),
 * then the Keycloak user invited (execute-actions e-mail), then the {@code ACCOUNT} membership of that user and the
 * tenant activated. The invitation is an external call (D48: persist, call, record), so it runs between two
 * transactions, never inside one. Idempotent: each step finds what an earlier delivery did (the account by username,
 * the user by e-mail at Keycloak, the membership by its unique key, an ACTIVE tenant), so a redelivery after a crash
 * halfway ends in the same state. The address is never logged.
 */
@Service
class AdministratorProvisioning {

    private static final Logger log = LoggerFactory.getLogger(AdministratorProvisioning.class);

    private final RoleRepository roles;
    private final UserAccountRepository accounts;
    private final TenancyApi tenancy;
    private final TransactionTemplate transaction;
    private final String tenantIssuer;
    private final Clock clock;

    AdministratorProvisioning(RoleRepository roles, UserAccountRepository accounts, TenancyApi tenancy,
                              PlatformTransactionManager transactions,
                              @Value("${restaurantpro.security.issuers.tenant:}") String tenantIssuer, Clock clock) {
        this.roles = roles;
        this.accounts = accounts;
        this.tenancy = tenancy;
        this.transaction = new TransactionTemplate(transactions);
        this.tenantIssuer = tenantIssuer;
        this.clock = clock;
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    void on(CompanyProvisioned event) {
        if (tenantIssuer.isBlank()) {
            throw new UnavailableException("IDP_UNAVAILABLE", "The tenant realm is not configured.", Map.of());
        }
        UUID administrator = transaction.execute(status -> administratorAccount(event.adminEmail()));
        String subject = tenancy.idp().inviteUser(event.adminEmail(), null);
        transaction.executeWithoutResult(status -> {
            tenancy.grant(new MembershipGrant(tenantIssuer, subject, MembershipKind.ACCOUNT, administrator,
                    clock.instant(), null));
            tenancy.activate(event.tenantId());
        });
        log.info("Tenant {} provisioned: administrator account {} invited and its membership granted",
                event.tenantId(), administrator);
    }

    private UUID administratorAccount(String email) {
        String username = UserAccount.normalize(email);
        return accounts.findByUsername(username).map(UserAccount::getId).orElseGet(() -> {
            Role role = roles.findAdministrator().orElseGet(() -> roles.saveAndFlush(Role.administrator(
                    clock.instant())));
            return accounts.saveAndFlush(UserAccount.open(username, null, Set.of(role.getId()), clock.instant()))
                    .getId();
        });
    }
}
