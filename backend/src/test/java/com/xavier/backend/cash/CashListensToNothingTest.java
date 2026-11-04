package com.xavier.backend.cash;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.context.event.EventListener;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * spec §7.7: credit, or a document issued without payment, never makes a cash movement. Cash guarantees its half by
 * deciding nothing on its own: it reacts to no event (an order closed, a document issued, a credit sale), and a
 * receipt exists only because settlement called {@code CashApi.recordReceipt} for a confirmed Payment.
 */
class CashListensToNothingTest {

    static final JavaClasses CASH = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.xavier.backend.cash");

    @Test
    void cashHasNoEventListener() {
        noMethods().should().beAnnotatedWith(ApplicationModuleListener.class)
                .orShould().beAnnotatedWith(TransactionalEventListener.class)
                .orShould().beAnnotatedWith(EventListener.class)
                .because("cash records only what settlement tells it (spec §7.5, §7.7)")
                .check(CASH);
    }
}
