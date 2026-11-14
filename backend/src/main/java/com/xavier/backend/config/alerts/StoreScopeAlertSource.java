package com.xavier.backend.config.alerts;

import com.xavier.backend.config.persistence.StoreScopeEnforcement;
import com.xavier.backend.shared.ops.AlertSeverity;
import com.xavier.backend.shared.ops.AlertSource;
import com.xavier.backend.shared.ops.OperationalAlert;
import com.xavier.backend.shared.persistence.StoreScopeViolations;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * P68/D170: {@code STORE_SCOPE_VIOLATION} (CRITICAL), one alert per kind, while row-level security refused anything in
 * the last 24 h (each refusal is a bug: {@code AccessPolicy} allowed it); {@code STORE_SCOPE_PERMISSIVE} (CRITICAL)
 * for as long as the emergency switch is on. Installation-wide (no store) and with no ids: the ERROR log has the scope
 * origin.
 */
@Component
class StoreScopeAlertSource implements AlertSource {

    static final String VIOLATION = "STORE_SCOPE_VIOLATION";
    static final String PERMISSIVE = "STORE_SCOPE_PERMISSIVE";

    private final StoreScopeViolations violations;
    private final StoreScopeEnforcement enforcement;
    private final Instant startedAt;

    StoreScopeAlertSource(StoreScopeViolations violations, Environment environment, Clock clock) {
        this.violations = violations;
        this.enforcement = StoreScopeEnforcement.of(environment);
        this.startedAt = clock.instant();
    }

    @Override
    public List<OperationalAlert> alerts() {
        List<OperationalAlert> alerts = new ArrayList<>();
        if (enforcement == StoreScopeEnforcement.PERMISSIVE) {
            alerts.add(new OperationalAlert(PERMISSIVE, AlertSeverity.CRITICAL, null, 1, startedAt,
                    "Row-level security is not filtering (store-scope enforcement PERMISSIVE): restart with ENFORCE "
                            + "once the cause is fixed."));
        }
        violations.lastDay().forEach((kind, recent) -> alerts.add(new OperationalAlert(VIOLATION,
                AlertSeverity.CRITICAL, null, recent.count(), recent.first(), "Row-level security refused "
                + recent.count() + " " + kind.label() + " in the last 24 h (" + kind.code() + "): a store scope "
                + "bug; see the ERROR log.")));
        return alerts;
    }
}
