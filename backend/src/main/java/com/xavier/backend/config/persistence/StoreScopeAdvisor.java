package com.xavier.backend.config.persistence;

import com.xavier.backend.access.StoreScopes;
import com.xavier.backend.access.SystemScope;
import com.xavier.backend.access.SystemScoped;
import com.xavier.backend.shared.persistence.StoreScopeViolations;
import com.xavier.backend.shared.tenancy.ActiveTenants;
import com.xavier.backend.shared.tenancy.BootstrapTenant;
import com.xavier.backend.shared.tenancy.JobLeases;
import com.xavier.backend.shared.tenancy.TenantScopes;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.aopalliance.aop.Advice;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.Pointcut;
import org.springframework.aop.ProxyMethodInvocation;
import org.springframework.aop.support.AbstractPointcutAdvisor;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.support.ComposablePointcut;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.modulith.events.ApplicationModuleListener;

/**
 * Binds the tenant and the store scope of an actor-less entry point (D166, D185 as amended), ordered before the
 * transaction interceptor ({@value #ORDER_OFFSET} after the highest precedence) so the binding is in place before a
 * listener's {@code REQUIRES_NEW} transaction begins. {@code @Async} dispatch runs first (it is added before existing
 * advisors), so the binding is set on the executor thread.
 * <ul>
 *   <li>an {@link ApplicationModuleListener} method runs in its event's tenant, read from the event's record component
 *       {@code UUID tenantId()}, and in its event's store ({@code UUID storeId()}) or its {@link SystemScoped} scope.
 *       An event without a tenant, or without a store when the listener has no system scope, fails before the
 *       listener's transaction. Republication and the D88 recovery go through the same proxy, and the serialized event
 *       carries its tenant;</li>
 *   <li>a {@link SystemScoped} method of reach {@code ALL} runs once per ACTIVE tenant ({@link ActiveTenants}), from a
 *       rotating start, each under a {@link JobLeases} lease and in its own binding (the directory and the leases are
 *       one transaction, their release one more, S77; a tenant starts only while half its lease is left); one tenant's failure is counted
 *       ({@value #TENANT_FAILURES}{job}, never a tenant label, P74), logged with the tenant and does not stop the
 *       others. It returns the last tenant's result. Called by a thread that is already bound to a tenant (a request,
 *       a test), it runs once, in that tenant. {@link SystemScope#BOOTSTRAP} runs in the bootstrap tenant only;</li>
 *   <li>one of reach {@code NONE} runs once with no tenant (PLATFORM tables only).</li>
 * </ul>
 * No module job changes code. A refusal by row-level security escaping the method is counted and logged with the
 * scope origin (D170).
 */
class StoreScopeAdvisor extends AbstractPointcutAdvisor {

    static final int ORDER_OFFSET = 10;
    static final String TENANT_FAILURES = "system_job_tenant_failures";

    private static final Logger log = LoggerFactory.getLogger(StoreScopeAdvisor.class);
    private static final Map<Class<?>, Object> PRIMITIVE_DEFAULTS = Map.of(boolean.class, false, int.class, 0,
            long.class, 0L, short.class, (short) 0, byte.class, (byte) 0, char.class, (char) 0, float.class, 0f,
            double.class, 0d);

    private final transient ObjectProvider<StoreScopes> scopes;
    private final transient ObjectProvider<TenantScopes> tenants;
    private final transient ObjectProvider<ActiveTenants> activeTenants;
    private final transient ObjectProvider<JobLeases> leases;
    private final transient ObjectProvider<StoreScopeViolations> violations;
    private final transient ObjectProvider<MeterRegistry> meters;
    private final transient Map<String, AtomicInteger> rotation = new ConcurrentHashMap<>();
    private final transient Pointcut pointcut = new ComposablePointcut(
            AnnotationMatchingPointcut.forMethodAnnotation(SystemScoped.class))
            .union(AnnotationMatchingPointcut.forMethodAnnotation(ApplicationModuleListener.class));

    StoreScopeAdvisor(ObjectProvider<StoreScopes> scopes, ObjectProvider<TenantScopes> tenants,
                      ObjectProvider<ActiveTenants> activeTenants, ObjectProvider<JobLeases> leases,
                      ObjectProvider<StoreScopeViolations> violations, ObjectProvider<MeterRegistry> meters) {
        this.scopes = scopes;
        this.tenants = tenants;
        this.activeTenants = activeTenants;
        this.leases = leases;
        this.violations = violations;
        this.meters = meters;
        setOrder(Ordered.HIGHEST_PRECEDENCE + ORDER_OFFSET);
    }

    @Override
    public Pointcut getPointcut() {
        return pointcut;
    }

    @Override
    public Advice getAdvice() {
        return (MethodInterceptor) this::invoke;
    }

    private Object invoke(MethodInvocation invocation) throws Throwable {
        StoreScopes stores = scopes.getIfAvailable();
        if (stores == null) {
            return invocation.proceed();
        }
        TenantScopes tenantScopes = tenants.getIfAvailable();
        Class<?> target = invocation.getThis() == null ? invocation.getMethod().getDeclaringClass()
                : AopUtils.getTargetClass(invocation.getThis());
        Method method = AopUtils.getMostSpecificMethod(invocation.getMethod(), target);
        SystemScoped system = AnnotationUtils.findAnnotation(method, SystemScoped.class);
        SystemScope scope = system == null ? null : system.value();
        String name = target.getSimpleName() + "." + method.getName();
        if (AnnotationUtils.findAnnotation(method, ApplicationModuleListener.class) != null) {
            String origin = "listener:" + name;
            Object event = invocation.getArguments().length == 0 ? null : invocation.getArguments()[0];
            UUID storeId = component(event, "storeId");
            if (tenantScopes == null) {
                return counted(origin, () -> scope != null ? stores.call(scope, () -> proceed(invocation))
                        : stores.callForStore(storeId, origin, () -> proceed(invocation)));
            }
            UUID tenantId = component(event, "tenantId");
            return counted(origin, () -> tenantScopes.callForEvent(tenantId, scope, storeId, origin,
                    () -> proceed(invocation)));
        }
        if (scope == null) {
            return invocation.proceed();
        }
        String origin = scope.scope().origin();
        if (tenantScopes == null) {
            return counted(origin, () -> stores.call(scope, () -> proceed(invocation)));
        }
        if (scope.reach() == SystemScope.Reach.NONE) {
            return counted(origin, () -> tenantScopes.callUnbound(scope, () -> proceed(invocation)));
        }
        if (scope == SystemScope.BOOTSTRAP) {
            return counted(origin, () -> tenantScopes.call(BootstrapTenant.ID, scope, () -> proceed(invocation)));
        }
        if (tenantScopes.current().isPresent()) {
            return counted(origin, () -> stores.call(scope, () -> proceed(invocation)));
        }
        // the lease key: two classes may share a simple name, never a fully-qualified one
        return eachActiveTenant(invocation, method, target.getName() + "." + method.getName(), scope, tenantScopes);
    }

    private Object eachActiveTenant(MethodInvocation invocation, Method method, String job, SystemScope scope,
                                    TenantScopes tenantScopes) {
        Object result = PRIMITIVE_DEFAULTS.get(method.getReturnType());
        ActiveTenants active = activeTenants.getIfAvailable();
        if (active == null) {
            log.warn("{} did not run: no tenant directory in this context", job);
            return result;
        }
        JobLeases jobLeases = leases.getIfAvailable();
        // S77 (F3): the directory and every tenant's lease in one transaction, and one release after the tick
        JobLeases.Batch batch = jobLeases == null ? null : jobLeases.acquire(job, active::ids);
        List<UUID> ids = batch == null ? active.ids() : batch.tenants();
        if (ids.isEmpty()) {
            return result;
        }
        int start = Math.floorMod(rotation.computeIfAbsent(job, key -> new AtomicInteger()).getAndIncrement(),
                ids.size());
        String origin = scope.scope().origin();
        try {
            for (int i = 0; i < ids.size(); i++) {
                UUID tenantId = ids.get((start + i) % ids.size());
                if (batch != null && !batch.mayStart()) {
                    // D185: a tenant starts only with half its lease left; the rest run at the next tick. ponytail: a
                    // lease is not renewed while a run lasts, so one run longer than the ttl still lets a second
                    // instance start the same tenant (follow-up: renew, or size the ttl above the longest run)
                    log.warn("{} stopped before tenant {}: the tick outlasted half of restaurantpro.jobs.lease-ttl", job,
                            tenantId);
                    break;
                }
                result = runFor(invocation, job, scope, tenantScopes, tenantId, origin, result);
            }
        } finally {
            release(jobLeases, job, ids);
        }
        return result;
    }

    /** One tenant's run, in its own binding; a failure is counted and logged, and the others still run. */
    private Object runFor(MethodInvocation invocation, String job, SystemScope scope, TenantScopes tenantScopes,
                          UUID tenantId, String origin, Object previous) {
        try {
            // a fresh invocation per tenant: one invocation proceeds once, and a second proceed() would skip
            // every interceptor after this one (the method's own @Transactional among them)
            MethodInvocation run = ((ProxyMethodInvocation) invocation).invocableClone();
            return counted(origin, () -> tenantScopes.call(tenantId, scope, () -> proceed(run)));
        } catch (Error e) {
            throw e;
        } catch (Throwable e) {
            failed(job, tenantId, e);
            return previous;
        }
    }

    private void failed(String job, UUID tenantId, Throwable error) {
        MeterRegistry registry = meters.getIfAvailable();
        if (registry != null) {
            Counter.builder(TENANT_FAILURES).tag("job", job)
                    .description("A system job failed for one tenant; the others ran (D185)").register(registry)
                    .increment();
        }
        log.error("System job {} failed for tenant {}; the other tenants still ran", job, tenantId, error);
    }

    private static void release(JobLeases jobLeases, String job, List<UUID> tenantIds) {
        if (jobLeases == null) {
            return;
        }
        try {
            jobLeases.release(job, tenantIds);
        } catch (RuntimeException e) {
            log.warn("Could not release the leases of {}; they expire on their own", job, e);
        }
    }

    private Object counted(String origin, Supplier<Object> scoped) throws Throwable {
        try {
            return scoped.get();
        } catch (Tunnel tunnel) {
            throw recorded(tunnel.getCause(), origin);
        } catch (RuntimeException e) {
            throw recorded(e, origin);
        }
    }

    private Throwable recorded(Throwable error, String origin) {
        StoreScopeViolations counter = violations.getIfAvailable();
        if (counter != null) {
            StoreScopeViolations.classify(error).ifPresent(kind -> counter.record(kind, origin));
        }
        return error;
    }

    private static Object proceed(MethodInvocation invocation) {
        try {
            return invocation.proceed();
        } catch (Throwable e) {
            throw new Tunnel(e);
        }
    }

    /** The event's {@code UUID <name>()} record component, or {@code null}. */
    private static UUID component(Object event, String name) {
        if (event == null || !event.getClass().isRecord()) {
            return null;
        }
        for (RecordComponent component : event.getClass().getRecordComponents()) {
            if (component.getName().equals(name) && component.getType() == UUID.class) {
                try {
                    Method accessor = component.getAccessor();
                    accessor.setAccessible(true);
                    return (UUID) accessor.invoke(event);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("Cannot read " + name + " of " + event.getClass(), e);
                }
            }
        }
        return null;
    }

    /** Carries the method's own exception, checked or not, through the scope's {@code Supplier}. */
    private static final class Tunnel extends RuntimeException {

        Tunnel(Throwable cause) {
            super(cause);
        }
    }
}
