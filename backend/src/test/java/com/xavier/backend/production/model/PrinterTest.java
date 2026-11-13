package com.xavier.backend.production.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.production.exception.ProductionRuleViolationException;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Printer modes (spec §8.6, r2-decisions §4): REDIRECT names another printer of the same store, with no chains. */
class PrinterTest {

    static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");
    static final UUID STORE = UUID.randomUUID();

    @Test
    void aNewPrinterPrintsNormally() {
        Printer printer = printer(STORE, "KIT");

        assertThat(printer.getMode()).isEqualTo(PrinterMode.NORMAL);
        assertThat(printer.isActive()).isTrue();
        assertThat(printer.address()).isEqualTo(new PrinterAddress("127.0.0.1", 9100));
    }

    @Test
    void redirectNamesTheTargetAndLeavingRedirectForgetsIt() {
        Printer kitchen = printer(STORE, "KIT");
        Printer bar = printer(STORE, "BAR");

        kitchen.changeMode(PrinterMode.REDIRECT, bar, NOW);
        assertThat(kitchen.getRedirectPrinterId()).isEqualTo(bar.getId());

        kitchen.changeMode(PrinterMode.HOLD, null, NOW);
        assertThat(kitchen.getMode()).isEqualTo(PrinterMode.HOLD);
        assertThat(kitchen.getRedirectPrinterId()).isNull();
    }

    @Test
    void redirectNeedsAnotherPrinterOfTheSameStoreThatIsNotRedirectingItself() {
        Printer kitchen = printer(STORE, "KIT");
        Printer elsewhere = printer(UUID.randomUUID(), "BAR");
        Printer redirecting = printer(STORE, "PAS");
        redirecting.changeMode(PrinterMode.REDIRECT, printer(STORE, "COP"), NOW);

        assertRedirectInvalid(() -> kitchen.changeMode(PrinterMode.REDIRECT, null, NOW));
        assertRedirectInvalid(() -> kitchen.changeMode(PrinterMode.REDIRECT, kitchen, NOW));
        assertRedirectInvalid(() -> kitchen.changeMode(PrinterMode.REDIRECT, elsewhere, NOW));
        assertRedirectInvalid(() -> kitchen.changeMode(PrinterMode.REDIRECT, redirecting, NOW));
        assertRedirectInvalid(() -> kitchen.changeMode(PrinterMode.NORMAL, printer(STORE, "X"), NOW));
        assertThat(kitchen.getMode()).isEqualTo(PrinterMode.NORMAL);
    }

    private static void assertRedirectInvalid(Runnable change) {
        assertThatThrownBy(change::run).isInstanceOf(ProductionRuleViolationException.class)
                .hasFieldOrPropertyWithValue("code", "PRINTER_REDIRECT_INVALID");
    }

    static Printer printer(UUID store, String code) {
        Printer printer = Printer.create(store, code, "Impressora " + code, "127.0.0.1", 9100, CodePage.CP860,
                PrinterProfile.DEFAULT, NOW);
        try {
            Field id = Printer.class.getDeclaredField("id");
            id.setAccessible(true);
            id.set(printer, UUID.randomUUID());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        return printer;
    }
}
