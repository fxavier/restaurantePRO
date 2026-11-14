package com.xavier.backend.config.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.access.StoreScopes;
import com.xavier.backend.access.StrictStoreScope;
import com.xavier.backend.access.SystemScope;
import com.xavier.backend.architecture.SystemScopeRegistryTest;
import com.xavier.backend.organization.model.event.CompanyBootstrapped;
import com.xavier.backend.organization.model.event.StoreDeactivated;
import io.micrometer.core.instrument.MeterRegistry;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * A74 (S64 part): every actor-less entry point resolves its D166 scope on the connection it checks out, seen through a
 * probe of {@code current_setting} at each checkout. Strict: the JUnit thread has no fixture fallback, so a missing
 * scope shows as unset. While armed, the probe aborts each checkout after recording it, so a job's body writes nothing
 * to the shared database. Each module's own listeners and jobs are added by its slice in its own class.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, ActorlessStoreScopeTest.Probe.class})
@StrictStoreScope
class ActorlessStoreScopeTest {

    static final String ROOT = "com.xavier.backend.";
    /** Entry points that do not touch the database. */
    static final Set<String> NO_DATABASE = Set.of("shared.notify.ChangeNotifier.heartbeat");

    record Checkout(String thread, String allStores, String storeIds, String origin) {
    }

    @Autowired
    ApplicationContext context;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    StoreScopes scopes;
    @Autowired
    MeterRegistry registry;

    @AfterEach
    void disarm() {
        ProbeDataSource.armed = false;
        ProbeDataSource.abort = false;
        ProbeDataSource.seen.clear();
    }

    @Test
    void thePoolIsTheStoreScopeHook() {
        DataSource bean = context.getBean(DataSource.class);

        assertThat(((DelegatingDataSource) bean).getTargetDataSource()).isInstanceOf(StoreScopedDataSource.class);
    }

    @Test
    void theStrictTestThreadWithNoActorIsUnset() {
        arm(false);

        jdbc.sql("SELECT 1").query(Integer.class).single();

        assertThat(onThisThread()).singleElement().isEqualTo(new Checkout(Thread.currentThread().getName(), "", "",
                "none"));
    }

    static Stream<String> actorlessEntryPoints() {
        return SystemScopeRegistryTest.EXPECTED.keySet().stream()
                .filter(entryPoint -> !entryPoint.equals("identity.service.AdminBootstrapService.on"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("actorlessEntryPoints")
    void everyJobRunnerAndReadinessHookRunsInItsSystemScope(String entryPoint) throws Exception {
        SystemScope expected = SystemScopeRegistryTest.EXPECTED.get(entryPoint);
        arm(true);

        invoke(entryPoint);

        List<Checkout> checkouts = onThisThread();
        if (!NO_DATABASE.contains(entryPoint)) {
            assertThat(checkouts).as("it checked out a connection").isNotEmpty();
        }
        assertThat(checkouts).allSatisfy(checkout -> {
            assertThat(checkout.origin()).isEqualTo(expected.scope().origin());
            assertThat(checkout.allStores()).isEqualTo(expected.scope().allStoresSetting());
            assertThat(checkout.storeIds()).isEqualTo("{}");
        });
    }

    @Test
    void aStoreListenerRunsInItsEventsStoreOnTheListenerThread() throws Exception {
        UUID store = UUID.randomUUID();
        String origin = "listener:TerminalSessionService.on";
        arm(true);

        invoke("identity.service.TerminalSessionService", "on", new StoreDeactivated(store, Instant.now()));

        Checkout checkout = awaitCheckout(origin);
        assertThat(checkout.thread()).isNotEqualTo(Thread.currentThread().getName());
        assertThat(checkout.allStores()).isEqualTo("off");
        assertThat(checkout.storeIds()).isEqualTo("{" + store + "}");
    }

    @Test
    void theBootstrapListenerRunsInTheBootstrapScope() throws Exception {
        arm(true);

        invoke("identity.service.AdminBootstrapService", "on",
                new CompanyBootstrapped("C", "400000002", "MZ", "MZN", Instant.now()));

        assertThat(awaitCheckout("system:BOOTSTRAP").allStores()).isEqualTo("on");
    }

    @Test
    void aPaymentSubmissionRunsInTheSubmittersScopeOnItsWorker() throws Exception {
        UUID store = UUID.randomUUID();
        Object submitter = context.getBean(Class.forName(ROOT + "settlement.service.PaymentSubmitter"));
        Method submit = submitter.getClass().getMethod("submit", UUID.class);
        arm(true);

        scopes.callForStore(store, "listener:Probe.on", () -> {
            try {
                return submit.invoke(submitter, UUID.randomUUID());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });

        Checkout checkout = awaitCheckout("task:listener:Probe.on");
        assertThat(checkout.thread()).startsWith("payment-submitter-");
        assertThat(checkout.storeIds()).isEqualTo("{" + store + "}");
    }

    @Test
    void thePrintJobsGaugeIsInstallationWideUnderAScrapeWithNoActor() {
        arm(false);

        registry.get("print_jobs").tag("status", "QUEUED").gauge().value();

        assertThat(onThisThread()).isNotEmpty().allSatisfy(checkout -> {
            assertThat(checkout.origin()).isEqualTo("system:METRICS");
            assertThat(checkout.allStores()).isEqualTo("on");
        });
    }

    @Test
    void theOperationalAlertsAreCollectedInstallationWide() {
        arm(true);

        context.getBean("operationalAlertsHealthIndicator", HealthIndicator.class).health();

        assertThat(onThisThread()).isNotEmpty().allSatisfy(checkout -> {
            assertThat(checkout.origin()).isIn("system:ALERTS", "system:METRICS");
            assertThat(checkout.allStores()).isEqualTo("on");
        });
    }

    private void invoke(String entryPoint) throws Exception {
        int dot = entryPoint.lastIndexOf('.');
        String type = ROOT + entryPoint.substring(0, dot);
        String name = entryPoint.substring(dot + 1);
        Method method = Arrays.stream(Class.forName(type).getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(name)).findFirst().orElseThrow();
        Object[] arguments = method.getParameterCount() == 0 ? new Object[0]
                : new Object[] {new DefaultApplicationArguments()};
        call(context.getBean(Class.forName(type)), method, arguments);
    }

    private void invoke(String type, String name, Object event) throws Exception {
        Method method = Class.forName(ROOT + type).getDeclaredMethod(name, event.getClass());
        call(context.getBean(Class.forName(ROOT + type)), method, new Object[] {event});
    }

    private static void call(Object bean, Method method, Object[] arguments) throws IllegalAccessException {
        method.setAccessible(true);
        try {
            method.invoke(bean, arguments);
        } catch (InvocationTargetException e) {
            // the probe aborted the entry point after recording its first checkout
        }
    }

    private static Checkout awaitCheckout(String origin) {
        return Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> ProbeDataSource.seen.stream()
                .filter(checkout -> checkout.origin().equals(origin)).findFirst().orElse(null), found -> found != null);
    }

    private static List<Checkout> onThisThread() {
        String thread = Thread.currentThread().getName();
        return ProbeDataSource.seen.stream().filter(checkout -> checkout.thread().equals(thread)).toList();
    }

    private static void arm(boolean abort) {
        ProbeDataSource.seen.clear();
        ProbeDataSource.abort = abort;
        ProbeDataSource.armed = true;
    }

    /** Records the settings of every checkout while armed; aborts the checkout after recording when asked. */
    static final class ProbeDataSource extends DelegatingDataSource {

        static final List<Checkout> seen = new CopyOnWriteArrayList<>();
        static volatile boolean armed;
        static volatile boolean abort;

        ProbeDataSource(DataSource target) {
            super(target);
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection connection = super.getConnection();
            if (!armed) {
                return connection;
            }
            try (ResultSet row = connection.createStatement().executeQuery("SELECT current_setting('app.all_stores', "
                    + "true), current_setting('app.store_ids', true), current_setting('app.scope_origin', true)")) {
                row.next();
                seen.add(new Checkout(Thread.currentThread().getName(), row.getString(1), row.getString(2),
                        row.getString(3)));
            }
            if (abort) {
                connection.close();
                throw new SQLException("probe: stopped after recording the store scope");
            }
            return connection;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Probe {

        @Bean
        static BeanPostProcessor storeScopeCheckoutProbe() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    return bean instanceof StoreScopedDataSource scoped ? new ProbeDataSource(scoped) : bean;
                }
            };
        }
    }
}
