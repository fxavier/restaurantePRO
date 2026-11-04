-- BackOffice accounts (username + password, D26). Usernames are stored lower-case so uniqueness ignores case.
-- Only the password hash is stored, in Spring Security's delegating format ({bcrypt}…).
CREATE TABLE identity.user_account
(
    id                   UUID                     NOT NULL,
    username             VARCHAR(60)              NOT NULL,
    password_hash        VARCHAR(100)             NOT NULL,
    must_change_password BOOLEAN                  NOT NULL,
    active               BOOLEAN                  NOT NULL,
    employee_id          UUID,
    version              BIGINT                   NOT NULL,
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT user_account_pk PRIMARY KEY (id),
    CONSTRAINT user_account_username_uk UNIQUE (username),
    CONSTRAINT user_account_employee_uk UNIQUE (employee_id),
    CONSTRAINT user_account_employee_fk FOREIGN KEY (employee_id) REFERENCES identity.employee (id),
    CONSTRAINT user_account_username_format_ck CHECK (username = lower(btrim(username)) AND username <> ''),
    CONSTRAINT user_account_password_hash_format_ck CHECK (password_hash LIKE '{bcrypt}$2_$%')
);

-- Installation-wide roles of an account: every permission of the role is granted in every store (PERM_X).
CREATE TABLE identity.user_account_role
(
    user_account_id UUID NOT NULL,
    role_id         UUID NOT NULL,
    CONSTRAINT user_account_role_pk PRIMARY KEY (user_account_id, role_id),
    CONSTRAINT user_account_role_user_fk FOREIGN KEY (user_account_id) REFERENCES identity.user_account (id)
        ON DELETE CASCADE,
    CONSTRAINT user_account_role_role_fk FOREIGN KEY (role_id) REFERENCES identity.role (id)
);
