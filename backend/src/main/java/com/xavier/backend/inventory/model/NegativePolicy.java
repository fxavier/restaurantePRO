package com.xavier.backend.inventory.model;

/**
 * Whether a warehouse lets manual outgoing movements go below zero (D118, P41). Sales always post, whatever the
 * policy: availability before a sale is catalog's sold-out flag.
 */
public enum NegativePolicy {
    ALLOW,
    REFUSE_MANUAL
}
