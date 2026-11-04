package com.xavier.backend.fiscal;

import com.xavier.backend.fiscal.model.event.DocumentPrintout;

/**
 * Port to a fiscal regime (D54, P7). R2 ships only the non-certified {@code internal} adapter (B4); the certified
 * market adapter is its own run. {@code restaurantpro.fiscal.adapter} selects the adapter by {@link #code()}, and
 * the application refuses to start with a non-certified one unless {@code restaurantpro.fiscal.allow-non-certified}
 * is {@code true}.
 */
public interface FiscalAdapter {

    /** The selection key, e.g. {@code "internal"}. */
    String code();

    /** {@code false} → startup guard, health {@code NON_CERTIFIED}, and the non-fiscal mark on every printout. */
    boolean certified();

    /**
     * Called inside the issuing transaction, under the series lock: local work only, never a network call
     * (spec §7.3). A remote authority is contacted after commit (D48), designed in the market run.
     */
    IssueResult issue(IssueRequest request);

    /** The text to print for an issued document; {@code copy} for a reprint. */
    DocumentPrintout printout(IssuedDocument document, boolean copy);
}
