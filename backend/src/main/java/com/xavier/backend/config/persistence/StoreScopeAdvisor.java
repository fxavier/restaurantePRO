package com.xavier.backend.config.persistence;

import com.xavier.backend.access.StoreScopes;
import com.xavier.backend.access.SystemScoped;
import com.xavier.backend.shared.persistence.StoreScopeViolations;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.UUID;
import java.util.function.Supplier;
import org.aopalliance.aop.Advice;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.Pointcut;
import org.springframework.aop.support.AbstractPointcutAdvisor;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.support.ComposablePointcut;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.modulith.events.ApplicationModuleListener;

/**
 * Applies the store scope of an actor-less entry point (D166), ordered before the transaction interceptor
 * ({@value #ORDER_OFFSET} after the highest precedence) so the scope is in place before a listener's
 * {@code REQUIRES_NEW} transaction checks out its connection. {@code @Async} dispatch runs first (it is added before
 * existing advisors), so the scope is set on the executor thread.
 * <ul>
 *   <li>a {@link SystemScoped} method runs in its system scope;</li>
 *   <li>an {@link ApplicationModuleListener} method runs in the scope of its event's store, read from the event's
 *       record component {@code UUID storeId()}; an event with no such component, or a null one, fails before the
 *       listener runs (the listener needs {@code @SystemScoped}). Republication and the D88 recovery go through the
 *       same proxy.</li>
 * </ul>
 * A refusal by row-level security escaping the method is counted and logged with the scope origin (D170).
 */
class StoreScopeAdvisor extends AbstractPointcutAdvisor {

    static final int ORDER_OFFSET = 10;

    private final transient ObjectProvider<StoreScopes> scopes;
    private final transient ObjectProvider<StoreScopeViolations> violations;
    private final transient Pointcut pointcut = new ComposablePointcut(
            AnnotationMatchingPointcut.forMethodAnnotation(SystemScoped.class))
            .union(AnnotationMatchingPointcut.forMethodAnnotation(ApplicationModuleListener.class));

    StoreScopeAdvisor(ObjectProvider<StoreScopes> scopes, ObjectProvider<StoreScopeViolations> violations) {
        this.scopes = scopes;
        this.violations = violations;
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
        StoreScopes available = scopes.getIfAvailable();
        if (available == null) {
            return invocation.proceed();
        }
        Class<?> target = invocation.getThis() == null ? invocation.getMethod().getDeclaringClass()
                : AopUtils.getTargetClass(invocation.getThis());
        Method method = AopUtils.getMostSpecificMethod(invocation.getMethod(), target);
        SystemScoped system = AnnotationUtils.findAnnotation(method, SystemScoped.class);
        if (system != null) {
            String origin = system.value().scope().origin();
            return counted(origin, () -> available.call(system.value(), () -> proceed(invocation)));
        }
        String origin = "listener:" + target.getSimpleName() + "." + method.getName();
        UUID storeId = storeIdOf(invocation.getArguments());
        return counted(origin, () -> available.callForStore(storeId, origin, () -> proceed(invocation)));
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

    /** The event's {@code UUID storeId()} record component, or {@code null}. */
    private static UUID storeIdOf(Object[] arguments) {
        if (arguments.length == 0 || arguments[0] == null || !arguments[0].getClass().isRecord()) {
            return null;
        }
        for (RecordComponent component : arguments[0].getClass().getRecordComponents()) {
            if (component.getName().equals("storeId") && component.getType() == UUID.class) {
                try {
                    Method accessor = component.getAccessor();
                    accessor.setAccessible(true);
                    return (UUID) accessor.invoke(arguments[0]);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("Cannot read storeId of " + arguments[0].getClass(), e);
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
