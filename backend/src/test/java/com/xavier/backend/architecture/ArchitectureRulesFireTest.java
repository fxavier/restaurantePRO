package com.xavier.backend.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Proves no rule is vacuous: every rule must report a violation on the deliberately bad fixture
 * {@code com.xavier.archfixture.bad} (acceptance criterion 9: "an injected illegal dependency makes
 * them fail"). The fixture lives outside {@code com.xavier.backend} so Spring never scans it.
 */
class ArchitectureRulesFireTest {

    static final JavaClasses BAD_FIXTURE = new ClassFileImporter().importPackages("com.xavier.archfixture.bad");

    static ArchRule[] rules() {
        return ArchitectureRules.ALL;
    }

    @ParameterizedTest
    @MethodSource("rules")
    void everyRuleCatchesItsViolationInTheBadFixture(ArchRule rule) {
        EvaluationResult result = rule.evaluate(BAD_FIXTURE);

        assertThat(result.hasViolation()).as(rule.getDescription()).isTrue();
    }

}
