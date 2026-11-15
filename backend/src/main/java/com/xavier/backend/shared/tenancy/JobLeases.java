package com.xavier.backend.shared.tenancy;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Job leases per {@code (job, tenant)} (D185 as amended): with several backend instances a job never runs twice for
 * the same tenant at once. {@link #tryAcquire} is a compare-and-set on {@code shared.job_lease.lease_until}
 * ({@code shared.try_lease}): it succeeds when no live lease exists or this instance holds it (a renewal); the lease
 * lasts {@code restaurantpro.jobs.lease-ttl} unless {@link #release}d earlier, so a crashed holder blocks for one
 * ttl at most. Each call is its own short transaction (PLATFORM table, no tenant bound).
 */
@Component
public class JobLeases {

    private final JdbcClient jdbc;
    private final TransactionTemplate inOwnTransaction;
    private final Duration ttl;
    private final String holder;

    @Autowired
    public JobLeases(JdbcClient jdbc, PlatformTransactionManager transactions,
                     @Value("${restaurantpro.jobs.lease-ttl:PT2M}") Duration ttl) {
        this(jdbc, transactions, ttl, "instance-" + UUID.randomUUID());
    }

    /** One instance's leases; {@code holder} names the instance (tests run two in one JVM). */
    public JobLeases(JdbcClient jdbc, PlatformTransactionManager transactions, Duration ttl, String holder) {
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("restaurantpro.jobs.lease-ttl must be positive: " + ttl);
        }
        this.jdbc = jdbc;
        this.inOwnTransaction = new TransactionTemplate(transactions);
        this.inOwnTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.ttl = ttl;
        this.holder = Objects.requireNonNull(holder, "holder");
    }

    /** @return whether this instance now holds the lease of {@code job} for {@code tenantId} */
    public boolean tryAcquire(String job, UUID tenantId) {
        return Boolean.TRUE.equals(inOwnTransaction.execute(status -> jdbc.sql(
                        "SELECT shared.try_lease(:job, :tenant, :holder, :ttl * interval '1 millisecond')")
                .param("job", job).param("tenant", tenantId).param("holder", holder).param("ttl", ttl.toMillis())
                .query(Boolean.class).single()));
    }

    /** Ends this instance's lease now, so another instance may run the job for the tenant at its next tick. */
    public void release(String job, UUID tenantId) {
        inOwnTransaction.executeWithoutResult(status -> jdbc.sql("""
                        UPDATE shared.job_lease SET lease_until = clock_timestamp()
                        WHERE job = :job AND tenant_id = :tenant AND holder = :holder""")
                .param("job", job).param("tenant", tenantId).param("holder", holder).update());
    }
}
