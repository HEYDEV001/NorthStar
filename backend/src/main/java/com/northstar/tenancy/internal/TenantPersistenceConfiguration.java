package com.northstar.tenancy.internal;

import com.northstar.tenancy.TenantScope;
import jakarta.persistence.EntityManagerFactory;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.aopalliance.intercept.MethodInterceptor;
import org.hibernate.cfg.MultiTenancySettings;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.repository.core.support.RepositoryFactoryBeanSupport;
import org.springframework.orm.jpa.JpaTransactionManager;

/** Wires the tenant guard, the tenant-aware transaction manager and Hibernate's tenant resolver (D7, step 1.4). */
@Configuration(proxyBeanMethods = false)
class TenantPersistenceConfiguration {

    /** Only the app's own datasource is guarded; the app_admin datasource (Phase 8) will be a separate bean. */
    static final String APP_DATASOURCE = "dataSource";

    @Bean
    static BeanPostProcessor tenantGuardDataSourcePostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (APP_DATASOURCE.equals(beanName)
                        && bean instanceof DataSource dataSource
                        && !(bean instanceof TenantGuardDataSource)) {
                    return new TenantGuardDataSource(dataSource);
                }
                return bean;
            }
        };
    }

    /**
     * Every repository call needs a tenant context before anything else runs, transaction and query creation
     * included. Without this, a declared query method outside a transaction opens a session first, and Spring Data
     * reports the refusal as a JPQL grammar error, which hides the real cause. Object methods stay callable.
     */
    @Bean
    static BeanPostProcessor repositoriesRequireTenantContext() {
        MethodInterceptor requireContext = invocation -> {
            if (invocation.getMethod().getDeclaringClass() != Object.class) TenantScope.require();
            return invocation.proceed();
        };
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof RepositoryFactoryBeanSupport<?, ?, ?> repositoryFactory) {
                    repositoryFactory.addRepositoryFactoryCustomizer(factory -> factory.addRepositoryProxyPostProcessor(
                            (proxy, repository) -> proxy.addAdvice(0, requireContext)));
                }
                return bean;
            }
        };
    }

    /**
     * Closes the guard's startup window once every singleton exists, Hibernate's schema validation included. Spring
     * calls this before it starts the web server, so no request ever runs while the window is open.
     */
    @Bean
    SmartInitializingSingleton closeTenantGuardStartupWindow(DataSource dataSource) {
        TenantGuardDataSource guard = guardOf(dataSource);
        return guard::closeStartupWindow;
    }

    /** Replaces Spring Boot's default, which backs off when a transactionManager bean exists. */
    @Bean
    JpaTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
        return new TenantAwareJpaTransactionManager(entityManagerFactory);
    }

    @Bean
    HibernatePropertiesCustomizer tenantIdentifierResolver(DataSource dataSource) {
        TenantIdentifierResolver resolver = new TenantIdentifierResolver(guardOf(dataSource));
        return properties -> properties.put(MultiTenancySettings.MULTI_TENANT_IDENTIFIER_RESOLVER, resolver);
    }

    private static TenantGuardDataSource guardOf(DataSource dataSource) {
        try {
            return dataSource.unwrap(TenantGuardDataSource.class);
        } catch (SQLException e) {
            throw new IllegalStateException("The app datasource is not tenant-guarded", e);
        }
    }
}
