package com.xavier.backend.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import java.util.Arrays;
import java.util.List;
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

    /** Each D35, D65 and D99 "NOT" edge is present in {@code com.xavier.archfixture.forbidden}; each rule must report it. */
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

    /** D99: an inventory call to a facade write is caught; read-only calls, its own services and audit are not. */
    @Test
    void anInventoryCallToAFacadeWriteIsCaughtAndAReadOnlyCallIsNot() {
        EvaluationResult result = ArchitectureRules.inventoryCallsOnlyReadOnlyFacadeMethods(FORBIDDEN_ROOT)
                .evaluate(FORBIDDEN_FIXTURE);

        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails())
                .anySatisfy(line -> assertThat(line).contains("StockConsumption").contains("OrdersFacade.close()"))
                .noneSatisfy(line -> assertThat(line).contains("OrdersFacade.find()"))
                .noneSatisfy(line -> assertThat(line).contains("CountingFacade.count()"))
                .noneSatisfy(line -> assertThat(line).contains("StockLedger.post()"))
                .noneSatisfy(line -> assertThat(line).contains("AuditTrail.record()"));
    }

    /** D99: an orders → inventory edge is caught; reporting reading inventory is allowed. */
    @Test
    void onlyReportingMayDependOnInventory() {
        ArchRule onlyReporting = Arrays.stream(forbiddenModuleDependencies())
                .filter(rule -> rule.getDescription().startsWith("no module but reporting depends on inventory"))
                .findFirst().orElseThrow();

        EvaluationResult result = onlyReporting.evaluate(FORBIDDEN_FIXTURE);

        assertThat(result.getFailureReport().getDetails())
                .anySatisfy(line -> assertThat(line).contains("StockShortcut").contains("StockLedger"))
                .anySatisfy(line -> assertThat(line).contains("StockRefund").contains("StockLedger"))
                .noneSatisfy(line -> assertThat(line).contains("StockReport"));
    }

    /**
     * A53 (S54): promotions and inventory are real modules now, so no D99 rule may pass vacuously. Each one fails on
     * an import with no class in it instead of passing, so a moved or renamed package cannot silence it.
     */
    @Test
    void noD99RuleCanPassVacuously() {
        JavaClasses nothing = new ClassFileImporter().importPackages("com.xavier.archfixture.empty");
        List<ArchRule> d99 = Arrays.stream(ArchitectureRules.forbiddenModuleDependencies("com.xavier.backend"))
                .filter(rule -> rule.getDescription().contains("(D99")).toList();

        assertThat(nothing).isEmpty();
        assertThat(d99).as("promotions ↛ 8, inventory ↛ 7, orders ↛ inventory, only reporting → inventory")
                .hasSize(17);
        for (ArchRule rule : d99) {
            assertThatThrownBy(() -> rule.check(nothing)).as(rule.getDescription())
                    .hasMessageContaining("failed to check any classes");
        }
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

    /** D137: OAuth2 outside identity and config is caught; identity's and config's own use is not. */
    @Test
    void anOAuth2DependencyOutsideIdentityAndConfigIsCaught() {
        EvaluationResult result = ArchitectureRules.onlyIdentityAndConfigUseOAuth2(FORBIDDEN_ROOT)
                .evaluate(FORBIDDEN_FIXTURE);

        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails())
                .anySatisfy(line -> assertThat(line).contains("OidcShortcut").contains("OidcUser"))
                .noneSatisfy(line -> assertThat(line).contains("OidcLogin"))
                .noneSatisfy(line -> assertThat(line).contains("OidcChain"));
    }

    /** D137: shared.ops reaching into a module is caught; its use of the shared kernel is not. */
    @Test
    void aSharedOpsDependencyOnAModuleIsCaught() {
        EvaluationResult result = ArchitectureRules.sharedOpsDependsOnNoModule(FORBIDDEN_ROOT)
                .evaluate(FORBIDDEN_FIXTURE);

        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails())
                .anySatisfy(line -> assertThat(line).contains("LeakyAlertSource").contains("OrderTotals"))
                .noneSatisfy(line -> assertThat(line).contains("KernelAlertSource"));
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

    /** D166 (A74): an unscoped job, runner or readiness hook fails the build; a @SystemScoped job does not. */
    @Test
    void anActorlessEntryPointWithoutASystemScopeIsCaught() {
        EvaluationResult result = ArchitectureRules.scheduledEntryPointsAreSystemScoped(FORBIDDEN_ROOT)
                .evaluate(FORBIDDEN_FIXTURE);

        assertThat(result.getFailureReport().getDetails())
                .anySatisfy(line -> assertThat(line).contains("UnscopedJob.tick()"))
                .anySatisfy(line -> assertThat(line).contains("UnscopedRunner.run("))
                .anySatisfy(line -> assertThat(line).contains("UnscopedReadiness.ready()"))
                .noneSatisfy(line -> assertThat(line).contains("ScopedJob.tick()"));
    }

    /** D166 (A74): a listener of an event without storeId fails the build unless it is @SystemScoped. */
    @Test
    void aListenerOfAStorelessEventWithoutASystemScopeIsCaught() {
        EvaluationResult result = ArchitectureRules.listenersHaveAStoreOrASystemScope(FORBIDDEN_ROOT)
                .evaluate(FORBIDDEN_FIXTURE);

        assertThat(result.getFailureReport().getDetails())
                .anySatisfy(line -> assertThat(line).contains("StorelessListener.on("))
                .noneSatisfy(line -> assertThat(line).contains("$StoreListener.on("))
                .noneSatisfy(line -> assertThat(line).contains("ScopedStorelessListener.on("));
    }

    /** D166: an executor submission without StoreScopes.propagate is caught; a propagated one is not. */
    @Test
    void anExecutorSubmissionWithoutPropagationIsCaught() {
        EvaluationResult result = ArchitectureRules.executorSubmissionsPropagateTheStoreScope(FORBIDDEN_ROOT)
                .evaluate(FORBIDDEN_FIXTURE);

        assertThat(result.getFailureReport().getDetails())
                .anySatisfy(line -> assertThat(line).contains("UnpropagatedSubmitter"))
                .noneSatisfy(line -> assertThat(line).contains("PropagatedSubmitter submits"));
    }

    /** D165: Hikari outside config is caught; config's own use is not. */
    @Test
    void hikariOutsideConfigIsCaught() {
        EvaluationResult result = ArchitectureRules.onlyConfigUsesHikari(FORBIDDEN_ROOT).evaluate(FORBIDDEN_FIXTURE);

        assertThat(result.getFailureReport().getDetails())
                .anySatisfy(line -> assertThat(line).contains("PoolOutsideConfig"))
                .noneSatisfy(line -> assertThat(line).contains("PoolInConfig"));
    }
}
