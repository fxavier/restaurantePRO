package com.xavier.backend.production.model.dto;

import com.xavier.backend.production.model.CodePage;
import com.xavier.backend.production.model.PrinterProfile;

/** A preset that fills the printer form (D154), with the code page offered for a new printer (P60: CP850). */
public record PrinterPresetView(String name, CodePage codePage, PrinterProfileView profile) {

    public static PrinterPresetView from(PrinterProfile.Preset preset) {
        return new PrinterPresetView(preset.name(), CodePage.CP850, PrinterProfileView.from(preset.profile()));
    }
}
