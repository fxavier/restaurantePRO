package com.xavier.backend.production.model;

/** How a printer ends a ticket (D154): {@code GS V 66 0} (partial), {@code GS V 65 0} (full) or no cut. */
public enum CutMode {
    PARTIAL,
    FULL,
    NONE
}
