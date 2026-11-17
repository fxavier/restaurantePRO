package com.xavier.backend.identity.service;

import com.xavier.backend.identity.model.SupportGrant;
import com.xavier.backend.identity.repository.SupportGrantRepository;
import com.xavier.backend.shared.ops.AlertSeverity;
import com.xavier.backend.shared.ops.AlertSource;
import com.xavier.backend.shared.ops.OperationalAlert;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * D183: while support acts in the tenant (a grant used and not ended), its administrators see it in the in-app
 * alerts ({@code SUPPORT_ACCESS_ACTIVE}, tenant-wide): computed in the tenant of whoever lists the alerts, so it never
 * shows another tenant's support.
 */
@Component
class SupportAccessAlerts implements AlertSource {

    static final String CODE = "SUPPORT_ACCESS_ACTIVE";

    private final SupportGrantRepository grants;
    private final Clock clock;

    SupportAccessAlerts(SupportGrantRepository grants, Clock clock) {
        this.grants = grants;
        this.clock = clock;
    }

    @Override
    public Scope scope() {
        return Scope.TENANT;
    }

    @Override
    @Transactional(readOnly = true)
    public List<OperationalAlert> alerts() {
        List<SupportGrant> inUse = grants.findInUse(clock.instant());
        if (inUse.isEmpty()) {
            return List.of();
        }
        Instant since = inUse.stream().map(SupportGrant::getFirstUsedAt).min(Comparator.naturalOrder()).orElseThrow();
        return List.of(new OperationalAlert(CODE, AlertSeverity.WARNING, null, inUse.size(), since,
                "Support is acting in this company under " + inUse.size() + " active support grant(s)."));
    }
}
