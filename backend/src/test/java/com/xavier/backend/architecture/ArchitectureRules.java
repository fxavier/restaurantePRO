package com.xavier.backend.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.properties.CanBeAnnotated.Predicates.annotatedWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

/**
 * Layer and type rules Spring Modulith does not cover (see docs/architecture/backend-conventions.md).
 * Each rule is checked against the production code ({@link ArchitectureTests}) and shown to fire on a
 * deliberately bad fixture ({@link ArchitectureRulesFireTest}).
 */
final class ArchitectureRules {

    private ArchitectureRules() {
    }

    private static final DescribedPredicate<JavaClass> ENTITY =
            annotatedWith(Entity.class).<JavaClass>forSubtype()
                    .or(annotatedWith(Embeddable.class).forSubtype()).as("JPA entities/embeddables");

    private static final DescribedPredicate<JavaClass> REPOSITORY = resideInAPackage("..repository..")
            .or(annotatedWith(Repository.class).forSubtype())
            .or(assignableTo(org.springframework.data.repository.Repository.class))
            .as("repositories");

    private static final DescribedPredicate<JavaClass> CONTROLLER =
            resideInAPackage("..controller..").or(annotatedWith(RestController.class).forSubtype()).as("controllers");

    private static final Set<String> FLOATING_POINT = Set.of("double", "float", "java.lang.Double", "java.lang.Float");

    /** Controllers are thin: they call services and speak DTOs, never repositories or entities (D12). */
    static final ArchRule CONTROLLERS_DO_NOT_TOUCH_REPOSITORIES_OR_ENTITIES = noClasses()
            .that(CONTROLLER)
            .should().dependOnClassesThat(REPOSITORY.or(ENTITY))
            .allowEmptyShould(true)
            .because("controllers call one service method and return DTOs (D12, spec §8.2)");

    /** Persistence is reached only through a use case (service), which owns the transaction. */
    static final ArchRule REPOSITORIES_ARE_USED_ONLY_BY_SERVICES = classes()
            .that(REPOSITORY)
            .should().onlyBeAccessed().byClassesThat(
                    resideInAnyPackage("..service..").or(annotatedWith(Service.class).forSubtype()).or(REPOSITORY))
            .because("use cases and transaction boundaries live in services");

    /** Entities never cross a module boundary or the HTTP boundary through DTOs/events. */
    static final ArchRule ENTITIES_STAY_OUT_OF_DTOS_AND_EVENTS = classes()
            .that().resideInAnyPackage("..dto..", "..event..")
            .should(new com.tngtech.archunit.lang.ArchCondition<JavaClass>("not be or reference a JPA entity") {
                @Override
                public void check(JavaClass item, com.tngtech.archunit.lang.ConditionEvents events) {
                    if (ENTITY.test(item)) {
                        events.add(com.tngtech.archunit.lang.SimpleConditionEvent.violated(item,
                                item.getName() + " is an entity inside a dto/event package"));
                    }
                    item.getDirectDependenciesFromSelf().stream()
                            .filter(dependency -> ENTITY.test(dependency.getTargetClass()))
                            .forEach(dependency -> events.add(com.tngtech.archunit.lang.SimpleConditionEvent
                                    .violated(item, dependency.getDescription())));
                }
            })
            .allowEmptyShould(true)
            .because("DTOs and events carry ids and snapshot values, never entities (D12)");

    /** Money is BigDecimal-based {@code Money}; floating point is banned outright (D15). */
    static final ArchRule NO_FLOATING_POINT_FIELDS = noFields()
            .should().haveRawType(describe("a floating-point type",
                    (JavaClass type) -> FLOATING_POINT.contains(type.getName())))
            .because("money and quantities are exact: use Money / BigDecimal (D15)");

    static final ArchRule NO_FLOATING_POINT_IN_MODEL_SIGNATURES = methods()
            .that().areDeclaredInClassesThat().resideInAPackage("..model..")
            .should().haveRawReturnType(describe("a non floating-point type",
                    (JavaClass type) -> !FLOATING_POINT.contains(type.getName())))
            .allowEmptyShould(true)
            .because("money and quantities are exact: use Money / BigDecimal (D15)");

    /** Time comes from the injected Clock so tests and business days are deterministic (D16). */
    static final ArchRule TIME_COMES_FROM_THE_CLOCK = noClasses()
            .should().callMethodWhere(describe("a zero-argument now() of java.time",
                    (JavaMethodCall call) -> call.getName().equals("now")
                            && call.getTarget().getParameterTypes().isEmpty()
                            && Set.of(Instant.class.getName(), LocalDate.class.getName(),
                                    LocalDateTime.class.getName(), LocalTime.class.getName(),
                                    OffsetDateTime.class.getName(), ZonedDateTime.class.getName())
                            .contains(call.getTargetOwner().getName())))
            .because("inject java.time.Clock and call now(clock) / clock.instant() (D16)");

    /** Constructor injection with final fields only. */
    static final ArchRule NO_FIELD_INJECTION = fields()
            .should().notBeAnnotatedWith(Autowired.class)
            .allowEmptyShould(true)
            .because("use constructor injection with final fields");

    /** Published events are immutable records (spec §7). */
    static final ArchRule EVENTS_ARE_RECORDS = classes()
            .that().resideInAPackage("..model.event..")
            .should().beRecords()
            .allowEmptyShould(true)
            .because("events are immutable facts named in the past tense");

    /**
     * Coded errors (stable {@code code}, extra properties) extend the shared NotFound/Conflict/BusinessRule
     * exceptions, which the GlobalExceptionHandler renders; module-local ErrorResponseException workarounds drifted
     * (S7b). The one exception is a status that is none of 404/409/422 and needs a header: the 429 login throttle.
     */
    static final ArchRule CODED_ERRORS_USE_THE_SHARED_EXCEPTIONS = noClasses()
            .that().doNotHaveFullyQualifiedName("com.xavier.backend.identity.exception.LoginThrottledException")
            .should().beAssignableTo(org.springframework.web.ErrorResponseException.class)
            .because("extend shared BusinessRuleException/ConflictException/NotFoundException with a code (S7b)");

    /**
     * The D35 "NOT" edges, which {@code ApplicationModules.verify()} cannot pin: it only checks a module against its
     * own {@code allowedDependencies}, so adding {@code "settlement"} to orders' list would still pass. These rules
     * name the forbidden edges directly. {@code root} is the base package ({@code com.xavier.backend} in production,
     * a fixture package in {@link ArchitectureRulesFireTest}), so the same rules are proven to fire.
     */
    static ArchRule[] forbiddenModuleDependencies(String root) {
        return new ArchRule[] {
                mustNotDependOn(root, "orders", "settlement",
                        "settlement reads orders (due, close), never the other way round (D35, spec §6)"),
                mustNotDependOn(root, "orders", "production",
                        "production tells orders a batch is dispatched; orders never calls production (D35)"),
                mustNotDependOn(root, "cash", "orders",
                        "cash records money movements and knows no orders; settlement links them (D35, spec §6)"),
                mustNotDependOn(root, "cash", "settlement",
                        "settlement calls cash (CashApi), never the other way round (D35)"),
                noOtherModuleDependsOn(root, "production",
                        "production is a leaf: it reacts to events and calls others, nobody calls it (D35)"),
                // D65 (R3); allowEmptyShould: customers and reporting may not exist yet
                mustNotDependOn(root, "fiscal", "customers",
                        "settlement passes the customer snapshot as the document's buyer; fiscal never reads live "
                                + "customer data (D65, spec §8.2)").allowEmptyShould(true),
                mustNotDependOn(root, "customers", "orders", CUSTOMERS_OWN_ONLY_THEIR_LEDGER).allowEmptyShould(true),
                mustNotDependOn(root, "customers", "settlement", CUSTOMERS_OWN_ONLY_THEIR_LEDGER)
                        .allowEmptyShould(true),
                mustNotDependOn(root, "customers", "fiscal", CUSTOMERS_OWN_ONLY_THEIR_LEDGER).allowEmptyShould(true),
                mustNotDependOn(root, "customers", "cash", CUSTOMERS_OWN_ONLY_THEIR_LEDGER).allowEmptyShould(true),
                mustNotDependOn(root, "customers", "production", CUSTOMERS_OWN_ONLY_THEIR_LEDGER)
                        .allowEmptyShould(true),
                noOtherModuleDependsOn(root, "reporting",
                        "reporting is a read-only projection: nothing depends on it (D65, spec §8.1)")
                        .allowEmptyShould(true),
                // D99 (R4); allowEmptyShould: promotions and inventory are skeletons until Group 1
                mustNotDependOn(root, "promotions", "orders", PROMOTIONS_GET_THEIR_FACTS_PASSED)
                        .allowEmptyShould(true),
                mustNotDependOn(root, "promotions", "settlement", PROMOTIONS_GET_THEIR_FACTS_PASSED)
                        .allowEmptyShould(true),
                mustNotDependOn(root, "promotions", "fiscal", PROMOTIONS_GET_THEIR_FACTS_PASSED)
                        .allowEmptyShould(true),
                mustNotDependOn(root, "promotions", "cash", PROMOTIONS_GET_THEIR_FACTS_PASSED).allowEmptyShould(true),
                mustNotDependOn(root, "promotions", "production", PROMOTIONS_GET_THEIR_FACTS_PASSED)
                        .allowEmptyShould(true),
                mustNotDependOn(root, "promotions", "inventory", PROMOTIONS_GET_THEIR_FACTS_PASSED)
                        .allowEmptyShould(true),
                mustNotDependOn(root, "promotions", "dining", PROMOTIONS_GET_THEIR_FACTS_PASSED)
                        .allowEmptyShould(true),
                mustNotDependOn(root, "promotions", "reporting", PROMOTIONS_GET_THEIR_FACTS_PASSED)
                        .allowEmptyShould(true),
                mustNotDependOn(root, "inventory", "settlement", INVENTORY_READS_ORDER_AND_CATALOG_FACTS)
                        .allowEmptyShould(true),
                mustNotDependOn(root, "inventory", "fiscal", INVENTORY_READS_ORDER_AND_CATALOG_FACTS)
                        .allowEmptyShould(true),
                mustNotDependOn(root, "inventory", "cash", INVENTORY_READS_ORDER_AND_CATALOG_FACTS)
                        .allowEmptyShould(true),
                mustNotDependOn(root, "inventory", "customers", INVENTORY_READS_ORDER_AND_CATALOG_FACTS)
                        .allowEmptyShould(true),
                mustNotDependOn(root, "inventory", "promotions", INVENTORY_READS_ORDER_AND_CATALOG_FACTS)
                        .allowEmptyShould(true),
                mustNotDependOn(root, "inventory", "dining", INVENTORY_READS_ORDER_AND_CATALOG_FACTS)
                        .allowEmptyShould(true),
                mustNotDependOn(root, "inventory", "production", INVENTORY_READS_ORDER_AND_CATALOG_FACTS)
                        .allowEmptyShould(true),
                mustNotDependOn(root, "orders", "inventory",
                        "stock consumes order facts through events; confirming a sale never waits on stock (D99, "
                                + "spec §5)").allowEmptyShould(true),
                noOtherModuleExceptDependsOn(root, "inventory", "reporting",
                        "a financial reversal never restores stock: only reporting reads inventory (D99, spec §8.4)")
                        .allowEmptyShould(true),
        };
    }

    private static final String PROMOTIONS_GET_THEIR_FACTS_PASSED = "promotions owns rules and the points ledger; "
            + "its callers pass the facts it needs, and orders calling it would otherwise form a cycle (D99)";

    private static final String INVENTORY_READS_ORDER_AND_CATALOG_FACTS = "stock consumes order facts through "
            + "persisted mechanisms and reads catalog; it knows no money, customers or promotions (D99, spec §6)";

    private static final String CUSTOMERS_OWN_ONLY_THEIR_LEDGER = "customers owns identity, limit and ledger; "
            + "settlement coordinates the charge without duplicating the balance (D65, spec §8.1)";

    /**
     * D65/D84: reporting reads, it never writes another module's facts (§8.1: a SalesRecord "nunca gera recebimento ou
     * stock"). A call from reporting to a facade method (a public method of an {@code @Service} in a module's root
     * package) must be {@code @Transactional(readOnly = true)}, on the method or, when the method declares none, on
     * its class. The audit trail is not a facade of facts: reporting records its exports (D86).
     */
    static ArchRule reportingCallsOnlyReadOnlyFacadeMethods(String root) {
        return callsOnlyReadOnlyFacadeMethods(root, "reporting",
                "reporting is a read-only projection; it never changes another module's facts (D65, D84)");
    }

    /**
     * D99: inventory consumes order and catalog facts, it never writes them. The same check as
     * {@link #reportingCallsOnlyReadOnlyFacadeMethods}: a call to another module's facade method must be
     * {@code @Transactional(readOnly = true)}; inventory's own services and the audit trail are not facades of facts.
     */
    static ArchRule inventoryCallsOnlyReadOnlyFacadeMethods(String root) {
        return callsOnlyReadOnlyFacadeMethods(root, "inventory",
                "stock consumes facts through persisted mechanisms and never writes to orders or catalog (D99, §6)");
    }

    private static ArchRule callsOnlyReadOnlyFacadeMethods(String root, String module, String why) {
        return noClasses()
                .that().resideInAPackage(root + "." + module + "..")
                .should().callMethodWhere(describe("a facade method that is not @Transactional(readOnly = true)",
                        (JavaMethodCall call) -> isFacadeOfAnotherModule(root, module, call.getTargetOwner())
                                && call.getTarget().resolveMember().map(method -> !isReadOnly(method)).orElse(true)))
                .allowEmptyShould(true)
                .as(module + " calls only read-only facade methods")
                .because(why);
    }

    /**
     * D71: {@code OrdersApi.voidLine} bypasses the PAYMENT lock because its caller holds {@code order_settlement} and
     * checked the headroom. Only settlement does both, so only settlement may call it.
     */
    static ArchRule onlySettlementCallsOrdersVoidLine(String root) {
        String ordersApi = root + ".orders.OrdersApi";
        return noClasses()
                .that().resideOutsideOfPackage(root + ".settlement..")
                .should().callMethodWhere(describe("OrdersApi.voidLine",
                        (JavaMethodCall call) -> call.getTargetOwner().getFullName().equals(ordersApi)
                                && call.getName().equals("voidLine")))
                .as("only settlement calls OrdersApi.voidLine")
                .because("it bypasses the PAYMENT lock: only settlement holds order_settlement and checks the "
                        + "headroom (D71)");
    }

    /**
     * D97 rule 4: every issue refreshes the document's payment snapshot first. Outside fiscal itself, no class but
     * settlement's {@code SettlementIssuer} calls {@code FiscalApi.issue} or {@code FiscalApi.recordPayments} (S35
     * review: the rule covers every module, not only settlement).
     */
    static ArchRule onlySettlementIssuerCallsFiscalIssue(String root) {
        String fiscalApi = root + ".fiscal.FiscalApi";
        return noClasses()
                .that().resideOutsideOfPackage(root + ".fiscal..")
                .and().doNotHaveFullyQualifiedName(root + ".settlement.service.SettlementIssuer")
                .should().callMethodWhere(describe("FiscalApi.issue or FiscalApi.recordPayments",
                        (JavaMethodCall call) -> call.getTargetOwner().getFullName().equals(fiscalApi)
                                && Set.of("issue", "recordPayments").contains(call.getName())))
                .as("only SettlementIssuer issues fiscal documents and records their payment snapshot")
                .because("issue refuses a draft without its payment snapshot, which SettlementIssuer records "
                        + "under order_settlement just before (D97)");
    }

    private static boolean isFacadeOfAnotherModule(String root, String caller, JavaClass owner) {
        String rootPackage = owner.getPackageName();
        if (!rootPackage.startsWith(root + ".")) {
            return false;
        }
        String module = rootPackage.substring(root.length() + 1);
        return !module.contains(".") && !Set.of(caller, "audit").contains(module)
                && owner.isAnnotatedWith(Service.class);
    }

    private static boolean isReadOnly(JavaMethod method) {
        return method.tryGetAnnotationOfType(Transactional.class)
                .or(() -> method.getOwner().tryGetAnnotationOfType(Transactional.class))
                .map(Transactional::readOnly)
                .orElse(false);
    }

    private static ArchRule mustNotDependOn(String root, String module, String forbidden, String why) {
        return noClasses()
                .that().resideInAPackage(root + "." + module + "..")
                .should().dependOnClassesThat().resideInAPackage(root + "." + forbidden + "..")
                .as(module + " must not depend on " + forbidden)
                .because(why);
    }

    private static ArchRule noOtherModuleExceptDependsOn(String root, String module, String allowed, String why) {
        return noClasses()
                .that().resideOutsideOfPackages(root + "." + module + "..", root + "." + allowed + "..")
                .should().dependOnClassesThat().resideInAPackage(root + "." + module + "..")
                .as("no module but " + allowed + " depends on " + module)
                .because(why);
    }

    private static ArchRule noOtherModuleDependsOn(String root, String module, String why) {
        return noClasses()
                .that().resideOutsideOfPackage(root + "." + module + "..")
                .should().dependOnClassesThat().resideInAPackage(root + "." + module + "..")
                .as("no module depends on " + module)
                .because(why);
    }

    static final ArchRule[] ALL = {
            CONTROLLERS_DO_NOT_TOUCH_REPOSITORIES_OR_ENTITIES,
            REPOSITORIES_ARE_USED_ONLY_BY_SERVICES,
            ENTITIES_STAY_OUT_OF_DTOS_AND_EVENTS,
            NO_FLOATING_POINT_FIELDS,
            NO_FLOATING_POINT_IN_MODEL_SIGNATURES,
            TIME_COMES_FROM_THE_CLOCK,
            NO_FIELD_INJECTION,
            EVENTS_ARE_RECORDS,
            CODED_ERRORS_USE_THE_SHARED_EXCEPTIONS,
    };
}
