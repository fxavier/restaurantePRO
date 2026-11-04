package com.xavier.backend.identity.service;

import com.xavier.backend.access.Actor;
import com.xavier.backend.identity.exception.InvalidCredentialsException;
import com.xavier.backend.identity.model.dto.SessionView;
import com.xavier.backend.identity.repository.EmployeeStoreAssignmentRepository;
import com.xavier.backend.organization.OrganizationApi;
import com.xavier.backend.organization.model.dto.StoreSummary;
import com.xavier.backend.organization.model.dto.TerminalSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * POS login: employee code + PIN on an enrolled terminal (D26). The session is scoped to the terminal's store:
 * it holds only the employee's authorities in that store (D27 amended). Every refusal is the same
 * {@link InvalidCredentialsException} (401); the reason goes to a WARN log line, never the PIN or the token.
 */
@Service
public class PosLoginService {

    private static final Logger log = LoggerFactory.getLogger(PosLoginService.class);

    private final OrganizationApi organization;
    private final PinVerification pins;
    private final EmployeeStoreAssignmentRepository assignments;
    private final AuthorityResolver authorities;

    PosLoginService(OrganizationApi organization, PinVerification pins,
                    EmployeeStoreAssignmentRepository assignments, AuthorityResolver authorities) {
        this.organization = organization;
        this.pins = pins;
        this.assignments = assignments;
        this.authorities = authorities;
    }

    /** Not transactional: the PIN check commits on its own so failures count even though this then throws. */
    public SessionView login(String deviceToken, String employeeCode, String pin) {
        TerminalSummary terminal = organization.findTerminalByDeviceToken(deviceToken)
                .orElseThrow(() -> refused(employeeCode, null, "unknown, revoked or missing terminal token"));
        if (!organization.findStore(terminal.storeId()).map(StoreSummary::active).orElse(false)) {
            throw refused(employeeCode, terminal, "store inactive");
        }
        PinCheck check = pins.verify(employeeCode, pin, terminal.storeId(), terminal.id());
        if (!check.isAccepted()) {
            throw refused(employeeCode, terminal, check.refusal());
        }
        if (assignments.findByEmployeeIdAndStoreId(check.employeeId(), terminal.storeId()).isEmpty()) {
            throw refused(employeeCode, terminal, "employee not assigned to the terminal's store");
        }
        Actor actor = new Actor(check.employeeId(), check.employeeId(), terminal.id(), terminal.storeId(),
                authorities.ofEmployeeInStore(check.employeeId(), terminal.storeId()));
        return SessionView.of(actor, check.name(), false);
    }

    private static InvalidCredentialsException refused(String employeeCode, TerminalSummary terminal, String reason) {
        log.warn("POS login refused for employee code '{}' on terminal {}: {}", employeeCode,
                terminal == null ? "(none)" : terminal.id(), reason);
        return new InvalidCredentialsException();
    }
}
