-- Employees log in on the POS with code + PIN (D26). Only the BCrypt hash of the PIN is stored, in Spring
-- Security's delegating format, so a plaintext PIN cannot be stored.
CREATE TABLE identity.employee
(
    id                  UUID                     NOT NULL,
    code                VARCHAR(20)              NOT NULL,
    name                VARCHAR(120)             NOT NULL,
    pin_hash            VARCHAR(100)             NOT NULL,
    failed_pin_attempts INT                      NOT NULL,
    locked              BOOLEAN                  NOT NULL,
    active              BOOLEAN                  NOT NULL,
    version             BIGINT                   NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT employee_pk PRIMARY KEY (id),
    CONSTRAINT employee_code_uk UNIQUE (code),
    CONSTRAINT employee_code_not_blank_ck CHECK (btrim(code) <> ''),
    CONSTRAINT employee_name_not_blank_ck CHECK (btrim(name) <> ''),
    CONSTRAINT employee_pin_hash_format_ck CHECK (pin_hash LIKE '{bcrypt}$2_$%'),
    -- five consecutive failures lock the employee (Employee.MAX_FAILED_PIN_ATTEMPTS)
    CONSTRAINT employee_failed_pin_attempts_ck CHECK (failed_pin_attempts BETWEEN 0 AND 5),
    CONSTRAINT employee_locked_after_five_failures_ck CHECK (failed_pin_attempts < 5 OR locked)
);

-- Employee × Store × Role (D27). store_id refers to organization.store: no cross-module FK (D6); the
-- service checks the store through OrganizationApi.
CREATE TABLE identity.employee_store_assignment
(
    id          UUID                     NOT NULL,
    employee_id UUID                     NOT NULL,
    store_id    UUID                     NOT NULL,
    role_id     UUID                     NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT employee_store_assignment_pk PRIMARY KEY (id),
    CONSTRAINT employee_store_assignment_employee_fk FOREIGN KEY (employee_id) REFERENCES identity.employee (id),
    CONSTRAINT employee_store_assignment_role_fk FOREIGN KEY (role_id) REFERENCES identity.role (id),
    CONSTRAINT employee_store_assignment_uk UNIQUE (employee_id, store_id, role_id)
);
CREATE INDEX employee_store_assignment_store_ix ON identity.employee_store_assignment (store_id);
