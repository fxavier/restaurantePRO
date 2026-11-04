package com.xavier.archfixture.bad.service;

import java.time.Instant;

/** Fixture: reads the system time instead of the injected Clock. */
public class ClocklessService {

    public Instant stamp() {
        return Instant.now();
    }
}
