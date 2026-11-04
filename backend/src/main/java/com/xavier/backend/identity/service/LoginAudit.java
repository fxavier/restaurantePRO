package com.xavier.backend.identity.service;

import com.xavier.backend.access.Actor; import com.xavier.backend.access.ActorKind;
import com.xavier.backend.audit.AuditEntry;
import com.xavier.backend.audit.AuditLog;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Successful logins and logouts in the audit trail (spec §10): who, and for POS sessions on which terminal of which
 * store. Refused logins are WARN log lines instead; they have no trustworthy actor.
 */
@Service
public class LoginAudit {

    private final AuditLog auditLog;

    LoginAudit(AuditLog auditLog) {
        this.auditLog = auditLog;
    }

    @Transactional
    public void loggedIn(Actor actor) {
        record(actor, "LOGGED_IN");
    }

    @Transactional
    public void loggedOut(Actor actor) {
        record(actor, "LOGGED_OUT");
    }

    private void record(Actor actor, String what) {
        boolean pos = actor.kind() == ActorKind.EMPLOYEE;
        auditLog.record(new AuditEntry(actor.userId(), null, (pos ? "EMPLOYEE_" : "USER_") + what,
                pos ? "Employee" : "UserAccount", actor.userId().toString(), actor.storeId(), actor.terminalId(), null,
                Map.of()));
    }
}
