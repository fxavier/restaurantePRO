package com.xavier.backend.production.model;

/** Whether a ticket has its printer (D89 (1)): UNROUTED work had no route or terminal printer, and has no job yet. */
public enum TicketRouting {
    ROUTED,
    UNROUTED
}
