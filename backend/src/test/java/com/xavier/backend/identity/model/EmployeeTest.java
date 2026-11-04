package com.xavier.backend.identity.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class EmployeeTest {

    static final Instant NOW = Instant.parse("2026-11-04T10:00:00Z");

    @Test
    void theFifthConsecutiveWrongPinLocksTheEmployee() {
        Employee employee = Employee.hire("W01", "Ana", "{bcrypt}hash", NOW);

        for (int i = 1; i <= 4; i++) {
            assertThat(employee.recordFailedPin(NOW)).as("failure %d locks", i).isFalse();
        }
        assertThat(employee.canLogIn()).isTrue();

        assertThat(employee.recordFailedPin(NOW)).isTrue();
        assertThat(employee.isLocked()).isTrue();
        assertThat(employee.canLogIn()).isFalse();
    }

    @Test
    void aCorrectPinResetsTheFailureCount() {
        Employee employee = Employee.hire("W01", "Ana", "{bcrypt}hash", NOW);
        for (int i = 0; i < 4; i++) {
            employee.recordFailedPin(NOW);
        }

        employee.recordSuccessfulPin(NOW);

        assertThat(employee.recordFailedPin(NOW)).as("count restarted").isFalse();
        assertThat(employee.getFailedPinAttempts()).isEqualTo(1);
    }

    @Test
    void unlockingClearsTheLockAndTheCount() {
        Employee employee = lockedEmployee();

        employee.unlock(NOW);

        assertThat(employee.canLogIn()).isTrue();
        assertThat(employee.getFailedPinAttempts()).isZero();
    }

    @Test
    void aNewPinAlsoUnlocks() {
        Employee employee = lockedEmployee();

        employee.resetPin("{bcrypt}other", NOW);

        assertThat(employee.canLogIn()).isTrue();
        assertThat(employee.getPinHash()).isEqualTo("{bcrypt}other");
        assertThat(employee.getFailedPinAttempts()).isZero();
    }

    @Test
    void anInactiveEmployeeCannotLogIn() {
        Employee employee = Employee.hire("W01", "Ana", "{bcrypt}hash", NOW);

        employee.update("Ana Maria", false, NOW);

        assertThat(employee.canLogIn()).isFalse();
        assertThat(employee.getName()).isEqualTo("Ana Maria");
    }

    @Test
    void theCodeAndNameAreTrimmed() {
        Employee employee = Employee.hire("  W01 ", " Ana ", "{bcrypt}hash", NOW);

        assertThat(employee.getCode()).isEqualTo("W01");
        assertThat(employee.getName()).isEqualTo("Ana");
    }

    private static Employee lockedEmployee() {
        Employee employee = Employee.hire("W01", "Ana", "{bcrypt}hash", NOW);
        for (int i = 0; i < Employee.MAX_FAILED_PIN_ATTEMPTS; i++) {
            employee.recordFailedPin(NOW);
        }
        return employee;
    }
}
