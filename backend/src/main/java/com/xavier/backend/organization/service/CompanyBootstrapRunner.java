package com.xavier.backend.organization.service;

import com.xavier.backend.access.SystemScope;
import com.xavier.backend.access.SystemScoped;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Runs the company bootstrap at startup (D30, the pre-SaaS path removed by S76). An invalid setting stops the
 * application. {@link SystemScope#BOOTSTRAP} binds the bootstrap tenant only (the scope advisor, D185): the
 * environment's company is that tenant's, never another one's; onboarding a tenant is {@code TenantProvisioning}.
 */
@Component
@EnableConfigurationProperties(CompanyBootstrapProperties.class)
class CompanyBootstrapRunner implements ApplicationRunner {

    private final CompanyBootstrapService bootstrap;
    private final CompanyBootstrapProperties settings;

    CompanyBootstrapRunner(CompanyBootstrapService bootstrap, CompanyBootstrapProperties settings) {
        this.bootstrap = bootstrap;
        this.settings = settings;
    }

    @SystemScoped(SystemScope.BOOTSTRAP)
    @Override
    public void run(ApplicationArguments args) {
        bootstrap.bootstrap(settings);
    }
}
