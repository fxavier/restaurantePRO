package com.xavier.bff;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
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
    private static final Pattern CANONICAL_UUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    record TenantSelection(String tenantId) {
    }

    static final class InvalidTenantSelectionException extends RuntimeException {
    }

    @PutMapping("/bff/tenant")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void select(@RequestBody TenantSelection selection, HttpSession session) {
        if (!isCanonicalUuid(selection.tenantId())) {
            throw new InvalidTenantSelectionException();
        }
        session.setAttribute(SELECTED, UUID.fromString(selection.tenantId()));
    }

    static boolean isCanonicalUuid(String value) {
        return value != null && CANONICAL_UUID.matcher(value).matches();
    }

    static Optional<UUID> selected(HttpSession session) {
        return Optional.ofNullable((UUID) session.getAttribute(SELECTED));
    }

    @ExceptionHandler(InvalidTenantSelectionException.class)
    ProblemDetail invalid() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "tenantId must be a UUID");
    }
}
