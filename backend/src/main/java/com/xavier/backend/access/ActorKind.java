package com.xavier.backend.access;

/** What kind of principal an {@link Actor} is (D38). */
public enum ActorKind {
    /** A BackOffice user account (username + password). {@code Actor.userId()} is the account id. */
    ACCOUNT,
    /** An employee logged in on an enrolled POS terminal (code + PIN). {@code Actor.userId()} is the employee id. */
    EMPLOYEE
}
