package com.xavier.backend.settlement.model;

/** BillPartition (§8.3, D67): LIVE → CANCELLED. Its financial state and "invoiced" are derived, never stored. */
public enum PartitionStatus {
    LIVE,
    CANCELLED
}
