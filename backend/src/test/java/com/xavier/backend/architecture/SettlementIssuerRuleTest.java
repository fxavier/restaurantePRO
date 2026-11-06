package com.xavier.backend.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.Test;

/**
 * D97 rule 4 over the real settlement module: no class but {@code SettlementIssuer} calls {@code FiscalApi.issue},
 * and the rule is not vacuous, because {@code SettlementIssuer} really does (the fixture side is in
 * {@link ArchitectureRulesFireTest}).
 */
class SettlementIssuerRuleTest {

    static final String ROOT = "com.xavier.backend";
    static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT);

    @Test
    void onlySettlementIssuerIssuesFiscalDocumentsInSettlement() {
        EvaluationResult result = ArchitectureRules.onlySettlementIssuerCallsFiscalIssue(ROOT).evaluate(PRODUCTION);

        assertThat(result.hasViolation()).as(result.getFailureReport().toString()).isFalse();
        assertThat(PRODUCTION.get(ROOT + ".settlement.service.SettlementIssuer").getMethodCallsFromSelf())
                .anySatisfy(call -> {
                    assertThat(call.getTargetOwner().getFullName()).isEqualTo(ROOT + ".fiscal.FiscalApi");
                    assertThat(call.getName()).isEqualTo("issue");
                });
    }
}
