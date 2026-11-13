package com.xavier.backend.production.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.production.exception.ProductionRule;
import com.xavier.backend.production.exception.ProductionRuleViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** D154: the ranges a profile must keep (also CHECKs in V7008), and the three presets. */
class PrinterProfileTest {

    @ParameterizedTest(name = "{0} mm × {1} columns")
    @CsvSource({"58, 24", "58, 35", "80, 36", "80, 64"})
    void theColumnRangeOfEachPaperWidthIsAccepted(int width, int columns) {
        assertThatCode(() -> profile(width, columns, null, 4, null, 100)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0} mm × {1} columns")
    @CsvSource({"58, 23", "58, 36", "80, 35", "80, 65", "76, 42"})
    void columnsOutsideTheirPaperWidthAreInvalid(int width, int columns) {
        assertInvalid(() -> profile(width, columns, null, 4, null, 100));
    }

    @ParameterizedTest(name = "pulse {0} ms")
    @CsvSource({"1", "0", "512", "101"})
    void aDrawerPulseOutside2To510MsOrOddIsInvalid(int pulse) {
        assertInvalid(() -> profile(80, 48, null, 4, null, pulse));
    }

    @Test
    void thePulseBoundsAreAccepted() {
        assertThatCode(() -> profile(80, 48, null, 4, 0, 2)).doesNotThrowAnyException();
        assertThatCode(() -> profile(80, 48, null, 4, 1, 510)).doesNotThrowAnyException();
    }

    @Test
    void aCodeTableFeedOrDrawerPinOutOfRangeIsInvalid() {
        assertInvalid(() -> profile(80, 48, 256, 4, null, 100));
        assertInvalid(() -> profile(80, 48, -1, 4, null, 100));
        assertInvalid(() -> profile(80, 48, null, 11, null, 100));
        assertInvalid(() -> profile(80, 48, null, -1, null, 100));
        assertInvalid(() -> profile(80, 48, null, 4, 2, 100));
        assertThatCode(() -> profile(80, 48, 255, 10, null, 100)).doesNotThrowAnyException();
        assertThatCode(() -> profile(80, 48, 0, 0, null, 100)).doesNotThrowAnyException();
    }

    @Test
    void aCutModeIsRequired() {
        assertInvalid(() -> new PrinterProfile(80, 48, null, null, 4, false, null, 100));
    }

    @Test
    void theDefaultIsTheR4Printer() {
        assertThat(PrinterProfile.DEFAULT).isEqualTo(new PrinterProfile(80, 48, null, CutMode.PARTIAL, 4, false,
                null, 100));
    }

    @Test
    void thePresetsAreAnEpsonTm80AGeneric80With42ColumnsAndAGeneric58() {
        assertThat(PrinterProfile.presets()).containsExactly(
                new PrinterProfile.Preset("EPSON_TM_80",
                        new PrinterProfile(80, 48, null, CutMode.PARTIAL, 4, true, null, 100)),
                new PrinterProfile.Preset("GENERIC_80_42",
                        new PrinterProfile(80, 42, null, CutMode.PARTIAL, 4, false, null, 100)),
                new PrinterProfile.Preset("GENERIC_58",
                        new PrinterProfile(58, 32, null, CutMode.NONE, 4, false, null, 100)));
    }

    @Test
    void theOverrideReplacesTheCodePagesEpsonTableNumber() {
        assertThat(PrinterProfile.DEFAULT.escPosTable(CodePage.CP850)).isEqualTo(2);
        assertThat(profile(80, 48, 17, 4, null, 100).escPosTable(CodePage.CP850)).isEqualTo(17);
    }

    private static PrinterProfile profile(int width, int columns, Integer codeTable, int feed, Integer pin,
                                          int pulse) {
        return new PrinterProfile(width, columns, codeTable, CutMode.PARTIAL, feed, false, pin, pulse);
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable make) {
        assertThatThrownBy(make).isInstanceOfSatisfying(ProductionRuleViolationException.class,
                e -> assertThat(e.rule()).isEqualTo(ProductionRule.PRINTER_PROFILE_INVALID));
    }
}
