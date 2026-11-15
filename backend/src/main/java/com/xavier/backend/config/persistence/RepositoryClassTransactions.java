package com.xavier.backend.config.persistence;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.aopalliance.aop.Advice;
import org.springframework.aop.Pointcut;
import org.springframework.aop.support.AbstractPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/**
 * D178 (as amended), for the hand-written {@code @Repository} classes (plain {@code JdbcClient}: {@code ON CONFLICT},
 * {@code NOWAIT}, locks, report queries): a public method without a {@code @Transactional} of its own, on a class
 * without one, runs in a {@code REQUIRED} transaction, so a call outside any transaction is bound like every other
 * instead of running unbound in autocommit (the D165 checkout hook used to scope it). Inside the caller's transaction
 * it joins, unchanged. Spring Data's repositories carry their own transactions ({@link RepositoryQueryTransactions}).
 */
class RepositoryClassTransactions extends AbstractPointcutAdvisor implements BeanFactoryAware {

    private final transient Pointcut pointcut = new AnnotationMatchingPointcut(Repository.class, true);
    private final transient TransactionInterceptor interceptor = new TransactionInterceptor();

    RepositoryClassTransactions() {
        interceptor.setTransactionAttributeSource(RepositoryClassTransactions::attribute);
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        interceptor.setBeanFactory(beanFactory);
    }

    @Override
    public Pointcut getPointcut() {
        return pointcut;
    }

    @Override
    public Advice getAdvice() {
        return interceptor;
    }

    private static DefaultTransactionAttribute attribute(Method method, Class<?> targetClass) {
        Class<?> type = targetClass == null ? method.getDeclaringClass() : targetClass;
        if (!Modifier.isPublic(method.getModifiers()) || method.getDeclaringClass() == Object.class
                || AnnotatedElementUtils.hasAnnotation(method, Transactional.class)
                || AnnotatedElementUtils.hasAnnotation(type, Transactional.class)) {
            return null;
        }
        return new DefaultTransactionAttribute(TransactionDefinition.PROPAGATION_REQUIRED);
    }
}
