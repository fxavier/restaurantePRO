package com.xavier.backend.production.model;

import com.xavier.backend.production.exception.ProductionRule;
import com.xavier.backend.production.exception.ProductionRuleViolationException;
import java.util.List;

/**
 * The variable ESC/POS parts of one printer (D154, ADR 0038): paper width and the layout width in Font A columns,
 * an optional {@code ESC t} table number for a vendor whose numbers differ from the code page's Epson number, the
 * cut, the feed before it, the status probe (D157) and the cash drawer (pin and pulse, used by the drawer kick).
 * The same ranges are CHECKs on {@code production.printer} (V7008).
 */
public record PrinterProfile(int paperWidthMm, int columns, Integer codeTable, CutMode cutMode, int feedLines,
                             boolean statusProbe, Integer drawerPin, int drawerPulseMs) {

    /** What every R4 printer gets (V7008 defaults): its tickets print byte-identical to R4. */
    public static final PrinterProfile DEFAULT = new PrinterProfile(80, 48, null, CutMode.PARTIAL, 4, false, null,
            100);

    private static final List<Preset> PRESETS = List.of(
            new Preset("EPSON_TM_80", new PrinterProfile(80, 48, null, CutMode.PARTIAL, 4, true, null, 100)),
            new Preset("GENERIC_80_42", new PrinterProfile(80, 42, null, CutMode.PARTIAL, 4, false, null, 100)),
            new Preset("GENERIC_58", new PrinterProfile(58, 32, null, CutMode.NONE, 4, false, null, 100)));

    public PrinterProfile {
        require(paperWidthMm == 58 || paperWidthMm == 80, "Paper width must be 58 or 80 mm");
        require(paperWidthMm == 58 ? columns >= 24 && columns <= 35 : columns >= 36 && columns <= 64,
                "A " + paperWidthMm + " mm printer has " + (paperWidthMm == 58 ? "24-35" : "36-64") + " columns");
        require(codeTable == null || codeTable >= 0 && codeTable <= 255, "The code table is 0-255");
        require(cutMode != null, "A cut mode is required");
        require(feedLines >= 0 && feedLines <= 10, "Feed lines are 0-10");
        require(drawerPin == null || drawerPin == 0 || drawerPin == 1, "The drawer pin is 0 (pin 2) or 1 (pin 5)");
        require(drawerPulseMs >= 2 && drawerPulseMs <= 510 && drawerPulseMs % 2 == 0,
                "The drawer pulse is an even number of ms from 2 to 510");
    }

    /** A named starting point for the BackOffice form; the stored fields stay authoritative. */
    public record Preset(String name, PrinterProfile profile) {
    }

    /** {@code EPSON_TM_80}, {@code GENERIC_80_42}, {@code GENERIC_58}. */
    public static List<Preset> presets() {
        return PRESETS;
    }

    /** The {@code ESC t} number: the override, else the code page's Epson number. */
    public int escPosTable(CodePage codePage) {
        return codeTable != null ? codeTable : codePage.escPosTable();
    }

    private static void require(boolean valid, String detail) {
        if (!valid) {
            throw new ProductionRuleViolationException(ProductionRule.PRINTER_PROFILE_INVALID, detail);
        }
    }
}
