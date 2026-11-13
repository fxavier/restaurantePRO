package com.xavier.backend.production.model.dto;

import com.xavier.backend.production.model.CutMode;
import com.xavier.backend.production.model.PrinterProfile;

/** A printer's ESC/POS profile (D154). {@code codeTable} and {@code drawerPin} are {@code null} when unset. */
public record PrinterProfileView(int paperWidthMm, int columns, Integer codeTable, CutMode cutMode, int feedLines,
                                 boolean statusProbe, Integer drawerPin, int drawerPulseMs) {

    public static PrinterProfileView from(PrinterProfile profile) {
        return new PrinterProfileView(profile.paperWidthMm(), profile.columns(), profile.codeTable(),
                profile.cutMode(), profile.feedLines(), profile.statusProbe(), profile.drawerPin(),
                profile.drawerPulseMs());
    }
}
