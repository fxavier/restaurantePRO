package com.xavier.bff;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The tenant the SPA chose among the caller's memberships (D177 amendment). The BFF only remembers the
 * choice and relays it as {@code X-Tenant-Id}; the backend decides whether the caller may use it.
 */
@RestController
class TenantController {

    private static final String SELECTED = TenantController.class.getName() + ".SELECTED";
    private static final String LOGGED_IN = TenantController.class.getName() + ".LOGGED_IN";
    private static final Pattern CANONICAL_UUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    record TenantSelection(String tenantId) {
    }

    static final class InvalidTenantSelectionException extends RuntimeException {
    }

    @PutMapping("/bff/tenant")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void select(@RequestBody TenantSelection selection, @AuthenticationPrincipal OidcUser user,
                HttpServletRequest request, HttpSession session) {
        ExpectedUser.check(request, ExpectedUser.subject(user));
        if (!isCanonicalUuid(selection.tenantId())) {
            throw new InvalidTenantSelectionException();
        }
        session.setAttribute(SELECTED, UUID.fromString(selection.tenantId()));
    }

    static boolean isCanonicalUuid(String value) {
        return value != null && CANONICAL_UUID.matcher(value).matches();
    }

    /**
     * After a login: another user logging in on this browser session (from another tab) starts with no selection, so
     * they never act in the previous user's choice; the same user's new login (a step-up) keeps it. {@code subject}
     * is the OIDC subject, the same id {@link ExpectedUser} checks.
     */
    static void keepSelectionOnlyFor(HttpSession session, String subject) {
        if (!subject.equals(session.getAttribute(LOGGED_IN))) {
            session.removeAttribute(SELECTED);
        }
        session.setAttribute(LOGGED_IN, subject);
    }

    static Optional<UUID> selected(HttpSession session) {
        return Optional.ofNullable((UUID) session.getAttribute(SELECTED));
    }

    @ExceptionHandler(InvalidTenantSelectionException.class)
    ProblemDetail invalid() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "tenantId must be a UUID");
    }
}
