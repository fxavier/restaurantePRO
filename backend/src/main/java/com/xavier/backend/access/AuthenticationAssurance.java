package com.xavier.backend.access;

/**
 * SPI: how strongly the current request's session is authenticated (D142). {@code identity} implements it (S56);
 * {@code access} cannot depend on {@code identity}. {@link AccessPolicy} asks it once per request, and only when a
 * BackOffice account uses a {@linkplain Permission#sensitive() sensitive} permission. No bean (a module test without
 * identity) means step-up is not enforced.
 */
public interface AuthenticationAssurance {

    /**
     * The assurance of the current request's session; {@link AssuranceState#NOT_ENFORCED} when MFA is off. {@code
     * enforced} follows the installation mode for every actor kind: an EMPLOYEE session gets {@code enforced = true}
     * under {@code SENSITIVE} or {@code ALL_BACKOFFICE}, so {@link AccessPolicy} refuses it a sensitive permission
     * outside the POS gate ({@code MFA_ACCOUNT_REQUIRED}).
     */
    AssuranceState current();
}
