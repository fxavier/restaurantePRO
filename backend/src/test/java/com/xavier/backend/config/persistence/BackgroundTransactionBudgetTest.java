package com.xavier.backend.config.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.shared.tenancy.ActiveTenants;
import com.xavier.backend.shared.tenancy.TenantTestSupport;
import com.xavier.backend.access.SystemScope;
import com.xavier.backend.access.SystemScoped;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.IntervalTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * S77 (A92, F2-F4): the background jobs' transaction rate at 20 ACTIVE tenants with no due work, measured with
 * {@code db_transactions}. The context's scheduler is stopped (hence {@link DirtiesContext}); each interval job of
 * reach {@code ALL} (the ones that grow with the tenants; the reach-NONE event maintenance sweeps the whole shared
 * registry and is left alone, T1) is then run once by hand, on a thread of its own as the scheduler would (no fixture tenant, no mock request), and its
 * transactions are divided by its interval. Measured at the tenants already ACTIVE and at 19 more, so the rate per
 * added tenant (the slope) is exact; the rate at 20 tenants is read on that line. The added tenants are closed after.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "restaurantpro.test.print-bridge=false") // a test stand-in, not a production job
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BackgroundTransactionBudgetTest {

    static final double MAX_RATE_AT_20 = 20.0;
    static final double MAX_SLOPE = 0.75;
    static final int ADDED = 19;

    private static final Logger log = LoggerFactory.getLogger(BackgroundTransactionBudgetTest.class);

    @Autowired
    List<ScheduledTaskHolder> holders;
    @Autowired
    ThreadPoolTaskScheduler scheduler;
    @Autowired
    ActiveTenants activeTenants;
    @Autowired
    MeterRegistry meters;

    @Test
    void atTwentyTenantsTheBackgroundJobsStayWithinTheirTransactionBudget() throws Exception {
        List<IntervalTask> jobs = stopTheSchedulerAndListTheIntervalJobs();
        assertThat(jobs).as("the interval jobs").isNotEmpty();
        TransactionCount count = new TransactionCount(meters);
        double failuresBefore = tenantFailures();

        int before = activeTenants.ids().size();
        Map<String, Double> rateBefore = ratePerJob(jobs, count);
        List<UUID> added = addActiveTenants();
        try {
            int after = activeTenants.ids().size();
            Map<String, Double> rateAfter = ratePerJob(jobs, count);

            double slope = (total(rateAfter) - total(rateBefore)) / (after - before);
            double rateAt20 = total(rateBefore) + slope * (20 - before);
            log.info("S77 background budget: {} tenants {} tx/s {}; {} tenants {} tx/s {}; slope {} tx/s per tenant; "
                            + "at 20 tenants {} tx/s", before, total(rateBefore), rateBefore, after,
                    total(rateAfter), rateAfter, slope, rateAt20);

            assertThat(after).isEqualTo(before + ADDED);
            assertThat(tenantFailures()).as("no tenant's run failed").isEqualTo(failuresBefore);
            assertThat(slope).as("transactions/s per added tenant, per job at %d tenants: %s", after, rateAfter)
                    .isLessThanOrEqualTo(MAX_SLOPE);
            assertThat(rateAt20).as("transactions/s at 20 tenants, per job at %d tenants: %s", after, rateAfter)
                    .isLessThanOrEqualTo(MAX_RATE_AT_20);
        } finally {
            added.forEach(tenant -> TenantTestSupport.ensureTenant(tenant, code(tenant), "CLOSED"));
        }
    }

    private List<IntervalTask> stopTheSchedulerAndListTheIntervalJobs() {
        List<IntervalTask> jobs = new ArrayList<>();
        for (ScheduledTaskHolder holder : holders) {
            for (ScheduledTask task : holder.getScheduledTasks()) {
                task.cancel(false);
                if (task.getTask() instanceof IntervalTask interval && reachesAllTenants(interval)) {
                    jobs.add(interval);
                }
            }
        }
        Awaitility.await().atMost(Duration.ofSeconds(60))
                .until(() -> scheduler.getScheduledThreadPoolExecutor().getActiveCount() == 0);
        return jobs;
    }

    /** The job's method ({@code ScheduledMethodRunnable}'s {@code class.method}) is {@code @SystemScoped} of reach ALL. */
    private static boolean reachesAllTenants(IntervalTask job) {
        String name = job.getRunnable().toString();
        int dot = name.lastIndexOf('.');
        try {
            for (Method method : Class.forName(name.substring(0, dot)).getDeclaredMethods()) {
                SystemScoped scoped = method.getAnnotation(SystemScoped.class);
                if (method.getName().equals(name.substring(dot + 1)) && scoped != null
                        && scoped.value().reach() == SystemScope.Reach.ALL) {
                    return true;
                }
            }
            return false;
        } catch (ClassNotFoundException | StringIndexOutOfBoundsException e) {
            return false; // a framework task (a lambda): not a module job
        }
    }

    /** Each job's transactions per second: one run's transactions over its interval. */
    private static Map<String, Double> ratePerJob(List<IntervalTask> jobs, TransactionCount count) throws Exception {
        Map<String, Double> rates = new LinkedHashMap<>();
        for (IntervalTask job : jobs) {
            Map<String, Long> before = count.snapshot();
            CompletableFuture.runAsync(job.getRunnable()).get(120, TimeUnit.SECONDS);
            long transactions = count.since(before).values().stream().mapToLong(Long::longValue).sum();
            rates.merge(job.getRunnable().toString(),
                    transactions * 1000.0 / job.getIntervalDuration().toMillis(), Double::sum);
        }
        return rates;
    }

    private static double total(Map<String, Double> rates) {
        return rates.values().stream().mapToDouble(Double::doubleValue).sum();
    }

    private static List<UUID> addActiveTenants() {
        List<UUID> added = new ArrayList<>();
        for (int i = 0; i < ADDED; i++) {
            UUID tenant = UUID.randomUUID();
            TenantTestSupport.ensureTenant(tenant, code(tenant), "ACTIVE");
            added.add(tenant);
        }
        return added;
    }

    private double tenantFailures() {
        return meters.find(StoreScopeAdvisor.TENANT_FAILURES).counters().stream().mapToDouble(Counter::count).sum();
    }

    private static String code(UUID tenant) {
        return "s77-budget-" + tenant;
    }
}
