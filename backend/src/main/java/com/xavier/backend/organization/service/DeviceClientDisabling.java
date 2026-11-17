package com.xavier.backend.organization.service;

import com.xavier.backend.organization.model.Terminal;
import com.xavier.backend.organization.model.event.TerminalRevoked;
import com.xavier.backend.organization.repository.TerminalRepository;
import com.xavier.backend.tenancy.TenancyApi;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A revoked device's Keycloak client is disabled (D182): its next token request fails at once (its membership was
 * already revoked with it, so its current token is refused too). An external call (D48), after the revocation
 * committed, outside any transaction; retried with the event until Keycloak answers. Idempotent: disabling a disabled
 * (or never created) client changes nothing.
 */
@Service
class DeviceClientDisabling {

    private static final Logger log = LoggerFactory.getLogger(DeviceClientDisabling.class);

    private final TerminalRepository terminals;
    private final TenancyApi tenancy;
    private final TransactionTemplate transaction;

    DeviceClientDisabling(TerminalRepository terminals, TenancyApi tenancy, PlatformTransactionManager transactions) {
        this.terminals = terminals;
        this.tenancy = tenancy;
        this.transaction = new TransactionTemplate(transactions);
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    void on(TerminalRevoked event) {
        Optional<String> client = transaction.execute(status -> terminals.findById(event.terminalId())
                .filter(Terminal::wasEnrolled).map(Terminal::clientId));
        if (client.isEmpty()) {
            return;
        }
        tenancy.idp().disableClient(client.get());
        log.info("Device {} revoked: its client is disabled", event.terminalId());
    }
}
