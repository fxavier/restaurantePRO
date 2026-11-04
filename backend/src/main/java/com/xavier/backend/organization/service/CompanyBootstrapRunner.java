package com.xavier.backend.organization.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/** Runs the company bootstrap at startup (D30). An invalid setting stops the application. */
@Component
@EnableConfigurationProperties(CompanyBootstrapProperties.class)
class CompanyBootstrapRunner implements ApplicationRunner {

    private final CompanyBootstrapService bootstrap;
    private final CompanyBootstrapProperties settings;

    CompanyBootstrapRunner(CompanyBootstrapService bootstrap, CompanyBootstrapProperties settings) {
        this.bootstrap = bootstrap;
        this.settings = settings;
    }

    @Override
    public void run(ApplicationArguments args) {
        bootstrap.bootstrap(settings);
    }
}
