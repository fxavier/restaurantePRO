package com.xavier.archfixture.forbidden.audit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Test fixture only: the audit trail, a write reporting may make for its exports (D86). */
@Service
public class AuditTrail {

    @Transactional(propagation = Propagation.MANDATORY)
    public void record() {
        // an audit row
    }
}
