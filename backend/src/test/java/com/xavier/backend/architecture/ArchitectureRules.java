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

    static final ArchRule[] ALL = {
            CONTROLLERS_DO_NOT_TOUCH_REPOSITORIES_OR_ENTITIES,
            REPOSITORIES_ARE_USED_ONLY_BY_SERVICES,
            ENTITIES_STAY_OUT_OF_DTOS_AND_EVENTS,
            NO_FLOATING_POINT_FIELDS,
            NO_FLOATING_POINT_IN_MODEL_SIGNATURES,
            TIME_COMES_FROM_THE_CLOCK,
            NO_FIELD_INJECTION,
            EVENTS_ARE_RECORDS,
    };
}
