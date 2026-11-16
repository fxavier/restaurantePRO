package com.xavier.backend.config.persistence;

import java.lang.reflect.Method;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.data.repository.core.support.RepositoryFactoryBeanSupport;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/**
 * D178 (as amended): the binding happens at transaction begin, so a statement outside any transaction carries no
 * tenant and no store scope (a protected table raises {@code RS003}/{@code RS001}). Spring Data makes its base
 * methods ({@code findById}, {@code save}, ...) transactional, but not the query methods a repository declares
 * ({@code findBy...}, {@code @Query}): called with no surrounding transaction, they ran in autocommit, which the D165
 * checkout hook used to scope. This post-processor gives every declared query method without a
 * {@code @Transactional} of its own a {@code REQUIRED} transaction: it joins the caller's, or opens a short one of its
 * own, bound like every other. Nothing else changes (one statement, read committed, as in autocommit); a job that calls
 * an external system between two repository calls still holds no transaction across the call (D48).
 */
class RepositoryQueryTransactions implements BeanPostProcessor, BeanFactoryAware {

    private BeanFactory beanFactory;

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof RepositoryFactoryBeanSupport<?, ?, ?> repository) {
            repository.addRepositoryFactoryCustomizer(factory -> factory.addRepositoryProxyPostProcessor(
                    (proxy, information) -> {
                        TransactionInterceptor interceptor = new TransactionInterceptor();
                        interceptor.setBeanFactory(beanFactory);
                        interceptor.setTransactionAttributeSource((method, targetClass) ->
                                information.isQueryMethod(method) && !declaresTransactional(method)
                                        ? new DefaultTransactionAttribute(TransactionDefinition.PROPAGATION_REQUIRED)
                                        : null);
                        proxy.addAdvice(interceptor);
                    }));
        }
        return bean;
    }

    private static boolean declaresTransactional(Method method) {
        return AnnotatedElementUtils.hasAnnotation(method, Transactional.class)
                || AnnotatedElementUtils.hasAnnotation(method.getDeclaringClass(), Transactional.class);
    }
}
