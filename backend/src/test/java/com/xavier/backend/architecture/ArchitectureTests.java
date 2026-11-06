package com.xavier.backend.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Applies {@link ArchitectureRules} to the production code. */
class ArchitectureTests {

    static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.xavier.backend");

    @Test
    void controllersDoNotTouchRepositoriesOrEntities() {
        ArchitectureRules.CONTROLLERS_DO_NOT_TOUCH_REPOSITORIES_OR_ENTITIES.check(PRODUCTION);
    }

    @Test
    void repositoriesAreUsedOnlyByServices() {
        ArchitectureRules.REPOSITORIES_ARE_USED_ONLY_BY_SERVICES.check(PRODUCTION);
    }

    @Test
    void entitiesStayOutOfDtosAndEvents() {
        ArchitectureRules.ENTITIES_STAY_OUT_OF_DTOS_AND_EVENTS.check(PRODUCTION);
    }

    @Test
    void noFloatingPointFields() {
        ArchitectureRules.NO_FLOATING_POINT_FIELDS.check(PRODUCTION);
    }

    @Test
    void noFloatingPointInModelSignatures() {
        ArchitectureRules.NO_FLOATING_POINT_IN_MODEL_SIGNATURES.check(PRODUCTION);
    }

    @Test
    void timeComesFromTheClock() {
        ArchitectureRules.TIME_COMES_FROM_THE_CLOCK.check(PRODUCTION);
    }

    @Test
    void noFieldInjection() {
        ArchitectureRules.NO_FIELD_INJECTION.check(PRODUCTION);
    }

    @Test
    void eventsAreRecords() {
        ArchitectureRules.EVENTS_ARE_RECORDS.check(PRODUCTION);
    }

    @Test
    void codedErrorsUseTheSharedExceptions() {
        ArchitectureRules.CODED_ERRORS_USE_THE_SHARED_EXCEPTIONS.check(PRODUCTION);
    }

    static ArchRule[] forbiddenModuleDependencies() {
        return ArchitectureRules.forbiddenModuleDependencies("com.xavier.backend");
    }

    /** D65: reporting reads; every facade method it calls is @Transactional(readOnly = true). */
    @Test
    void reportingCallsOnlyReadOnlyFacadeMethods() {
        ArchitectureRules.reportingCallsOnlyReadOnlyFacadeMethods("com.xavier.backend").check(PRODUCTION);
    }

    /** D99: inventory reads; every facade method it calls is @Transactional(readOnly = true). */
    @Test
    void inventoryCallsOnlyReadOnlyFacadeMethods() {
        ArchitectureRules.inventoryCallsOnlyReadOnlyFacadeMethods("com.xavier.backend").check(PRODUCTION);
    }

    /** D71: the coordinated void bypasses the PAYMENT lock, so only settlement calls OrdersApi.voidLine. */
    @Test
    void onlySettlementCallsOrdersVoidLine() {
        ArchitectureRules.onlySettlementCallsOrdersVoidLine("com.xavier.backend").check(PRODUCTION);
    }

    /**
     * D35: orders ↛ settlement, production; cash ↛ orders, settlement; nothing → production. D65: fiscal ↛ customers;
     * customers ↛ orders, settlement, fiscal, cash, production; nothing → reporting. D99: promotions ↛ orders,
     * settlement, fiscal, cash, production, inventory, dining, reporting; inventory ↛ settlement, fiscal, cash,
     * customers, promotions, dining, production; orders ↛ inventory; nothing but reporting → inventory.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("forbiddenModuleDependencies")
    void forbiddenModuleDependenciesAreAbsent(ArchRule rule) {
        rule.check(PRODUCTION);
    }
}
