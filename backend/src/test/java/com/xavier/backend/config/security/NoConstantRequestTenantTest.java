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
 * Security rule 1 (S71 review): no request runs in a constant tenant. The interim filter that bound every request to
 * the bootstrap tenant is gone; the only constant left is the POS chain's, until S73 ({@link InterimPosBootstrapTenant},
 * built by {@code SecurityConfig}'s POS chain alone): no other servlet filter may name the bootstrap tenant.
 */
class NoConstantRequestTenantTest {

    static final JavaClasses MAIN = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.xavier.backend");

    @Test
    void theInterimBootstrapTenantFilterOfS71IsGone() {
        assertThatThrownBy(() -> Class.forName("com.xavier.backend.shared.tenancy.BootstrapTenantRequests"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void onlyTheInterimPosFilterBindsTheBootstrapTenantToARequest() {
        noClasses().that().implement(Filter.class)
                .and().doNotHaveFullyQualifiedName(InterimPosBootstrapTenant.class.getName())
                .should().dependOnClassesThat().haveFullyQualifiedName(BootstrapTenant.class.getName())
                .because("a request's tenant comes from its membership (D177); S73 removes the POS exception")
                .check(MAIN);
    }

    @Test
    void onlyThePosChainBuildsTheInterimFilter() {
        noClasses().that().doNotHaveFullyQualifiedName(SecurityConfig.class.getName())
                .and().doNotHaveFullyQualifiedName(InterimPosBootstrapTenant.class.getName())
                .should().callConstructor(InterimPosBootstrapTenant.class, TenantScopesType.get())
                .check(MAIN);
    }

    /** The constructor's parameter type, by name (the filter's constructor takes {@code TenantScopes}). */
    private static final class TenantScopesType {
        static Class<?> get() {
            return com.xavier.backend.shared.tenancy.TenantScopes.class;
        }
    }
}
