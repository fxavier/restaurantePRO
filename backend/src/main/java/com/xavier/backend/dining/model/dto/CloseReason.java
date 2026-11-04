package com.xavier.backend.dining.model.dto;

/** Why a table occupation closed (r2-decisions §4 dining). {@code MOVED} is set only by a table move. */
public enum CloseReason {
    ORDER_CLOSED,
    ORDER_CANCELLED,
    MOVED
}
