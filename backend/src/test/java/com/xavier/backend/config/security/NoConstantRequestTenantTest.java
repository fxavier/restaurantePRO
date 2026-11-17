package com.xavier.backend.config.security;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.xavier.backend.shared.tenancy.BootstrapTenant;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;

/**
 * Security rule 1 (S71 review, S73): no request runs in a constant tenant. The interim filters that bound requests to
 * the bootstrap tenant are gone (S71's for every request, S72's for POS until S73 moved POS to the terminal's
 * membership): no servlet filter may name the bootstrap tenant.
 */
class NoConstantRequestTenantTest {

    static final JavaClasses MAIN = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.xavier.backend");

    @Test
    void theInterimBootstrapTenantFiltersAreGone() {
        assertThatThrownBy(() -> Class.forName("com.xavier.backend.shared.tenancy.BootstrapTenantRequests"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName("com.xavier.backend.config.security.InterimPosBootstrapTenant"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void noFilterBindsTheBootstrapTenantToARequest() {
        noClasses().that().implement(Filter.class)
                .should().dependOnClassesThat().haveFullyQualifiedName(BootstrapTenant.class.getName())
                .because("a request's tenant comes from its membership (D177), a terminal's too (D182)")
                .check(MAIN);
    }
}
