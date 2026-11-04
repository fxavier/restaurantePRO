package com.xavier.backend.identity.service;

import com.xavier.backend.access.Permission;
import com.xavier.backend.identity.ApprovalDeniedException;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Behind {@link com.xavier.backend.identity.ApproverVerifier}; see there for the contract. */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    private final PinVerification pins;
    private final AuthorityResolver authorities;

    ApprovalService(PinVerification pins, AuthorityResolver authorities) {
        this.pins = pins;
        this.authorities = authorities;
    }

    public UUID verify(String employeeCode, String pin, Permission permission, UUID storeId) {
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(storeId, "storeId");
        PinCheck check = pins.verify(employeeCode, pin, storeId, null);
        if (!check.isAccepted()) {
            throw refused(employeeCode, permission, storeId, check.refusal());
        }
        if (!authorities.ofEmployeeInStore(check.employeeId(), storeId).contains(permission.authority(storeId))) {
            throw refused(employeeCode, permission, storeId, "approver lacks the permission in this store");
        }
        return check.employeeId();
    }

    private static ApprovalDeniedException refused(String employeeCode, Permission permission, UUID storeId,
                                                   String reason) {
        log.warn("Approval refused for employee code '{}' ({} in store {}): {}", employeeCode, permission, storeId,
                reason);
        return new ApprovalDeniedException();
    }
}
