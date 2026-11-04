package com.xavier.backend.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

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
}
