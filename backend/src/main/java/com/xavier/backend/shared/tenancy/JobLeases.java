package com.xavier.backend.shared.tenancy;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Job leases per {@code (job, tenant)} (D185 as amended): with several backend instances a job never runs twice for
 * the same tenant at once. A tick {@link #acquire}s the leases of all its tenants in one short transaction
 * ({@code shared.try_leases}, S77): per tenant a compare-and-set on {@code shared.job_lease.lease_until} that succeeds
 * when no live lease exists or this instance holds it (a renewal). The leases last {@code restaurantpro.jobs.lease-ttl}
 * unless {@link #release}d earlier (one transaction), so a crashed holder blocks for one ttl at most; a tenant is run
 * only while its {@link Batch} is {@link Batch#live() live}. PLATFORM table, no tenant bound.
 */
@Component
public class JobLeases {

    /**
     * The tenants this instance holds the leases of, ordered by id, until {@code deadlineNanos} ({@link System#nanoTime}
     * before the leases were taken plus the ttl: never later than the leases' own end).
     */
    public record Batch(List<UUID> tenants, long deadlineNanos) {

        public Batch {
            tenants = List.copyOf(tenants);
        }

        /** Whether the leases are still this instance's: a tenant may start its run. */
        public boolean live() {
            return System.nanoTime() - deadlineNanos < 0;
        }
    }

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

    /**
     * Reads {@code tenants} and takes the leases of {@code job} for them, in one transaction (the read joins it).
     *
     * @return the tenants whose lease this instance now holds
     */
    public Batch acquire(String job, Supplier<List<UUID>> tenants) {
        long deadline = System.nanoTime() + ttl.toNanos();
        List<UUID> leased = inOwnTransaction.execute(status -> jdbc.sql("""
                        SELECT id FROM shared.try_leases(:job, :tenants, :holder, :ttl * interval '1 millisecond') AS id
                        ORDER BY id""")
                .param("job", job).param("tenants", tenants.get().toArray(UUID[]::new)).param("holder", holder)
                .param("ttl", ttl.toMillis()).query(UUID.class).list());
        return new Batch(leased == null ? List.of() : leased, deadline);
    }

    /** Ends this instance's leases of {@code job} for {@code tenants} now, so another instance may take them. */
    public void release(String job, Collection<UUID> tenants) {
        if (tenants.isEmpty()) {
            return;
        }
        inOwnTransaction.executeWithoutResult(status -> jdbc.sql(
                        "SELECT shared.release_leases(:job, :tenants, :holder)")
                .param("job", job).param("tenants", tenants.toArray(UUID[]::new)).param("holder", holder)
                .query().singleRow());
    }
}
