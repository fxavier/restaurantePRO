-- Spring Session JDBC tables (D25), from spring-session-jdbc 4.1.1 schema-postgresql.sql,
-- placed in the shared schema (spring.session.jdbc.table-name = shared.spring_session).
CREATE TABLE shared.spring_session
(
    primary_id            CHAR(36) NOT NULL,
    session_id            CHAR(36) NOT NULL,
    creation_time         BIGINT   NOT NULL,
    last_access_time      BIGINT   NOT NULL,
    max_inactive_interval INT      NOT NULL,
    expiry_time           BIGINT   NOT NULL,
    principal_name        VARCHAR(100),
    CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);
CREATE UNIQUE INDEX spring_session_ix1 ON shared.spring_session (session_id);
CREATE INDEX spring_session_ix2 ON shared.spring_session (expiry_time);
CREATE INDEX spring_session_ix3 ON shared.spring_session (principal_name);

CREATE TABLE shared.spring_session_attributes
(
    session_primary_id CHAR(36)     NOT NULL,
    attribute_name     VARCHAR(200) NOT NULL,
    attribute_bytes    BYTEA        NOT NULL,
    CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id)
        REFERENCES shared.spring_session (primary_id) ON DELETE CASCADE
);
