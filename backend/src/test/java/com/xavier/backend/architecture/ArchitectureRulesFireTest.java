package com.xavier.backend.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
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

    static final String FORBIDDEN_ROOT = "com.xavier.archfixture.forbidden";

    static final JavaClasses FORBIDDEN_FIXTURE = new ClassFileImporter().importPackages(FORBIDDEN_ROOT);

    static ArchRule[] forbiddenModuleDependencies() {
        return ArchitectureRules.forbiddenModuleDependencies(FORBIDDEN_ROOT);
    }

    /** Each D35 and D65 "NOT" edge is present in {@code com.xavier.archfixture.forbidden}; each rule must report it. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("forbiddenModuleDependencies")
    void everyForbiddenModuleDependencyIsCaught(ArchRule rule) {
        assertThat(rule.evaluate(FORBIDDEN_FIXTURE).hasViolation()).as(rule.getDescription()).isTrue();
    }

    /** D65 (A32): a reporting call to a facade write fails the build; a read-only facade call does not. */
    @Test
    void aReportingCallToAFacadeWriteIsCaughtAndAReadOnlyCallIsNot() {
        EvaluationResult result = ArchitectureRules.reportingCallsOnlyReadOnlyFacadeMethods(FORBIDDEN_ROOT)
                .evaluate(FORBIDDEN_FIXTURE);

        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails())
                .anySatisfy(line -> assertThat(line).contains("SalesReport").contains("OrdersFacade.close()"))
                .anySatisfy(line -> assertThat(line).contains("OrdersFacade.reopen()"))
                .noneSatisfy(line -> assertThat(line).contains("OrdersFacade.find()"))
                .noneSatisfy(line -> assertThat(line).contains("CountingFacade.count()"))
                .noneSatisfy(line -> assertThat(line).contains("AuditTrail.record()"));
    }

    /** D71: a call to OrdersApi.voidLine from outside settlement is caught; settlement's own call is not. */
    @Test
    void aVoidLineCallFromOutsideSettlementIsCaught() {
        EvaluationResult result = ArchitectureRules.onlySettlementCallsOrdersVoidLine(FORBIDDEN_ROOT)
                .evaluate(FORBIDDEN_FIXTURE);

        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails())
                .anySatisfy(line -> assertThat(line).contains("VoidShortcut").contains("OrdersApi.voidLine()"))
                .noneSatisfy(line -> assertThat(line).contains("SettledVoids"))
                .noneSatisfy(line -> assertThat(line).contains("OrdersApi.billingView()"));
    }

    /** D97: a settlement class other than SettlementIssuer calling FiscalApi.issue is caught; other calls are not. */
    @Test
    void aFiscalIssueOutsideSettlementIssuerIsCaught() {
        EvaluationResult result = ArchitectureRules.onlySettlementIssuerCallsFiscalIssue(FORBIDDEN_ROOT)
                .evaluate(FORBIDDEN_FIXTURE);

        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails())
                .anySatisfy(line -> assertThat(line).contains("IssueShortcut").contains("FiscalApi.issue()"))
                .noneSatisfy(line -> assertThat(line).contains("SettlementIssuer"))
                .noneSatisfy(line -> assertThat(line).contains("FiscalApi.coverage()"));
    }

    /** S35 review: the rule holds in every module, and covers recordPayments as well as issue. */
    @Test
    void aFiscalIssueOrSnapshotFromAnotherModuleIsCaught() {
        EvaluationResult result = ArchitectureRules.onlySettlementIssuerCallsFiscalIssue(FORBIDDEN_ROOT)
                .evaluate(FORBIDDEN_FIXTURE);

        assertThat(result.getFailureReport().getDetails())
                .anySatisfy(line -> assertThat(line).contains("DocumentReissue").contains("FiscalApi.issue()"))
                .anySatisfy(line -> assertThat(line).contains("DocumentReissue")
                        .contains("FiscalApi.recordPayments()"))
                .noneSatisfy(line -> assertThat(line).contains("service.SettlementIssuer"));
    }

    /** A15: an injected cash → orders dependency fails the build, naming the offending class. */
    @Test
    void aCashToOrdersDependencyIsCaught() {
        ArchRule cashMustNotDependOnOrders = Arrays.stream(forbiddenModuleDependencies())
                .filter(rule -> rule.getDescription().startsWith("cash must not depend on orders"))
                .findFirst().orElseThrow();

        EvaluationResult result = cashMustNotDependOnOrders.evaluate(FORBIDDEN_FIXTURE);

        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails())
                .anySatisfy(line -> assertThat(line).contains("CashDrawer").contains("OrderTotals"));
    }
}
