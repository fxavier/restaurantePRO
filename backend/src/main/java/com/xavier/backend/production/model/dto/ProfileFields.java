package com.xavier.backend.production.model.dto;

import com.xavier.backend.production.model.CutMode;
import com.xavier.backend.production.model.PrinterProfile;

/** The profile fields of a printer request; an omitted one takes its D154 default. */
final class ProfileFields {

    private ProfileFields() {
    }

    /** Columns default to 32 on 58 mm paper and 48 on 80 mm (each preset's width). */
    static PrinterProfile toProfile(Integer paperWidthMm, Integer columns, Integer codeTable, CutMode cutMode,
                                    Integer feedLines, Boolean statusProbe, Integer drawerPin, Integer drawerPulseMs) {
        PrinterProfile defaults = PrinterProfile.DEFAULT;
        int width = paperWidthMm != null ? paperWidthMm : defaults.paperWidthMm();
        return new PrinterProfile(width, columns != null ? columns : width == 58 ? 32 : defaults.columns(),
                codeTable, cutMode != null ? cutMode : defaults.cutMode(),
                feedLines != null ? feedLines : defaults.feedLines(),
                statusProbe != null ? statusProbe : defaults.statusProbe(), drawerPin,
                drawerPulseMs != null ? drawerPulseMs : defaults.drawerPulseMs());
    }
}
