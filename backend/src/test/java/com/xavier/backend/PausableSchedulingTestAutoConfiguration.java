package com.xavier.backend;

import java.util.concurrent.ScheduledExecutorService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * T1, test-only (registered in {@code META-INF/spring/...AutoConfiguration.imports} of the test resources): every
 * application context of the test JVM runs its {@code @Scheduled} jobs on a lifecycle-managed
 * {@link ThreadPoolTaskScheduler}, built like Boot's own ({@code spring.task.scheduling.*}: 4 threads,
 * {@code scheduling-}).
 *
 * <p>Why: the application enables scheduling in the {@code config} module, which an {@code @ApplicationModuleTest}
 * does not bootstrap. Scheduling is then enabled later in such a context (by an auto-configuration), after Boot's
 * {@code TaskSchedulingAutoConfiguration} has backed off for want of the scheduling processor, so the jobs ran on the
 * registrar's private single-thread executor ({@code pool-N-thread-1} in the logs). That executor is no Spring bean:
 * the test context cache cannot pause it. So every module-test context cached in the JVM kept running its jobs (the
 * event-publication recovery job and Modulith's staleness monitor among them) every minute, with its own, often
 * moved, clock, against the one shared database, while other test classes ran. With this scheduler a cached context is
 * paused between its test classes (Spring's {@code ON_CONTEXT_SWITCH} default), as a full {@code @SpringBootTest}
 * context already was.
 *
 * <p>In a context where Boot made its scheduler (scheduling enabled by the application's own configuration), this
 * configuration backs off: nothing changes there.
 */
@AutoConfiguration(after = TaskSchedulingAutoConfiguration.class)
@ConditionalOnMissingBean({TaskScheduler.class, ScheduledExecutorService.class})
public final class PausableSchedulingTestAutoConfiguration {

    @Bean(name = "taskScheduler")
    ThreadPoolTaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.build();
    }
}
