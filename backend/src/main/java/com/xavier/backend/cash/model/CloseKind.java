package com.xavier.backend.cash.model;

/** Who closed a cash session (P22): its operator, or a manager who force-closed it after it was abandoned. */
public enum CloseKind {
    OPERATOR,
    FORCED
}
