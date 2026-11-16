package com.xavier.backend.shared.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.DatabaseRoles;
import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.TestcontainersConfiguration;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * D185 (A90 mechanism): two backend instances never run the same {@code (job, tenant)} at once. Two
 * {@link JobLeases} with different holders, as the application role, on {@code shared.job_lease} (PLATFORM, no
 * tenant bound): a live lease of one instance refuses the other, its holder renews it, a release or an expiry hands
 * it over, and of two instances racing for a free lease exactly one wins. Latches, no sleeps.
 */
class JobLeaseTest {

    static final UUID TENANT = TenantTestSupport.A;

    final DriverManagerDataSource app = new DriverManagerDataSource(TestcontainersConfiguration.postgres()
            .getJdbcUrl(), DatabaseRoles.APP_ROLE, DatabaseRoles.appPassword());
    final JobLeases first = leases("instance-1");
    final JobLeases second = leases("instance-2");
    final ExecutorService instances = Executors.newFixedThreadPool(2);

    @AfterEach
    void stop() {
        instances.shutdownNow();
    }

    @Test
    void aLiveLeaseOfOneInstanceRefusesTheOtherAndItsHolderRenewsIt() {
        String job = job();

        assertThat(first.tryAcquire(job, TENANT)).isTrue();
        assertThat(second.tryAcquire(job, TENANT)).as("held by instance 1").isFalse();
        assertThat(first.tryAcquire(job, TENANT)).as("its holder renews it").isTrue();
        assertThat(second.tryAcquire(job, TenantTestSupport.B)).as("another tenant is another lease").isTrue();
    }

    @Test
    void aReleaseOrAnExpiryHandsTheLeaseOver() {
        String released = job();
        String expired = job();
        first.tryAcquire(released, TENANT);
        first.tryAcquire(expired, TENANT);

        first.release(released, TENANT);
        OwnerDatabase.shared().jdbc().sql("UPDATE shared.job_lease SET lease_until = now() - interval '1 second' "
                + "WHERE job = ?").param(expired).update();

        assertThat(second.tryAcquire(released, TENANT)).as("after a release").isTrue();
        assertThat(second.tryAcquire(expired, TENANT)).as("after an expiry (a crashed holder)").isTrue();
        assertThat(first.tryAcquire(released, TENANT)).as("now held by instance 2").isFalse();
    }

    @Test
    void whileOneInstanceRunsTheJobForATenantTheOtherSkipsIt() throws Exception {
        String job = job();
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        List<String> ran = java.util.Collections.synchronizedList(new ArrayList<>());

        Future<?> one = instances.submit(() -> runOnce(first, job, "instance-1", ran, running, finish));
        assertThat(running.await(30, TimeUnit.SECONDS)).isTrue();
        Future<?> two = instances.submit(() -> runOnce(second, job, "instance-2", ran, new CountDownLatch(1),
                new CountDownLatch(0)));
        two.get(30, TimeUnit.SECONDS);
        finish.countDown();
        one.get(30, TimeUnit.SECONDS);

        assertThat(ran).containsExactly("instance-1");
    }

    @Test
    void ofTwoInstancesRacingForAFreeLeaseExactlyOneWins() throws Exception {
        for (int round = 0; round < 20; round++) {
            String job = job();
            CountDownLatch start = new CountDownLatch(1);
            Future<Boolean> one = instances.submit(() -> {
                start.await();
                return first.tryAcquire(job, TENANT);
            });
            Future<Boolean> two = instances.submit(() -> {
                start.await();
                return second.tryAcquire(job, TENANT);
            });
            start.countDown();

            assertThat(List.of(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS)))
                    .as("round %d", round).containsExactlyInAnyOrder(true, false);
        }
    }

    /** One instance's tick for one tenant: run the body only under the lease, then release it. */
    private static void runOnce(JobLeases leases, String job, String instance, List<String> ran,
                                CountDownLatch running, CountDownLatch finish) {
        if (!leases.tryAcquire(job, TENANT)) {
            return;
        }
        try {
            ran.add(instance);
            running.countDown();
            finish.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            leases.release(job, TENANT);
        }
    }

    private JobLeases leases(String holder) {
        return new JobLeases(JdbcClient.create(app), new DataSourceTransactionManager(app), Duration.ofMinutes(2),
                holder);
    }

    private static String job() {
        return "JobLeaseTest." + UUID.randomUUID();
    }
}
