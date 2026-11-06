package com.xavier.backend.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.EvaluationResult;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The S19 rule {@code reportingCallsOnlyReadOnlyFacadeMethods} (D65, D84) over the real reporting module: it holds,
 * and it is not vacuous, because reporting really calls the owners' read facades it projects from.
 */
class ReportingReadOnlyRuleTest {

    static final String ROOT = "com.xavier.backend";
    static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT);

    @Test
    void reportingCallsOnlyReadOnlyFacadeMethodsOfTheModulesItProjects() {
        EvaluationResult result = ArchitectureRules.reportingCallsOnlyReadOnlyFacadeMethods(ROOT)
                .evaluate(PRODUCTION);

        assertThat(result.hasViolation()).as(result.getFailureReport().toString()).isFalse();
        assertThat(facadeCallsFromReporting()).contains(
                "FiscalApi.documentFacts", "FiscalApi.issuedDocumentIds",
                "OrdersApi.billingView", "OrdersApi.voidFacts", "OrdersApi.voidIds",
                "SettlementFacts.paymentFact", "SettlementFacts.refundFact", "SettlementFacts.creditChargeFact",
                "SettlementFacts.paymentIds", "SettlementFacts.refundIds", "SettlementFacts.creditChargeIds",
                "CatalogApi.snapshot", "DiningApi.tableInfo", "CashApi.findDay",
                "EmployeeDirectory.summaries", "OrganizationApi.findTerminal");
    }

    private static Set<String> facadeCallsFromReporting() {
        return PRODUCTION.stream().filter(type -> type.getPackageName().startsWith(ROOT + ".reporting"))
                .flatMap(type -> type.getMethodCallsFromSelf().stream())
                .map(call -> call.getTargetOwner().getSimpleName() + "." + call.getName())
                .collect(Collectors.toSet());
    }
}
