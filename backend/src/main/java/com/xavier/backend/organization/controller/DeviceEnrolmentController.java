package com.xavier.backend.organization.controller;

import com.xavier.backend.organization.exception.EnrolmentThrottledException;
import com.xavier.backend.organization.exception.InvalidDeviceKeyException;
import com.xavier.backend.organization.exception.InvalidEnrollmentCodeException;
import com.xavier.backend.organization.model.DeviceKind;
import com.xavier.backend.organization.model.dto.DeviceEnrolmentRequest;
import com.xavier.backend.organization.model.dto.DeviceEnrolmentResponse;
import com.xavier.backend.organization.service.DeviceEnrolmentService;
import com.xavier.backend.shared.exception.BusinessRuleException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * A device's enrolment with its one-time code (D182, D187), before it has any token: the code is its credential. A
 * terminal enrols under {@code /pos}, a print bridge under {@code /bridge}. The spec's codes for this route: 400
 * {@code ENROLMENT_CODE_INVALID} (one answer for every bad code), 429 {@code ENROLMENT_THROTTLED}; a key that is not a
 * public P-256 JWK is 400 {@code DEVICE_KEY_INVALID}.
 */
@RestController
class DeviceEnrolmentController {

    private final DeviceEnrolmentService enrolments;

    DeviceEnrolmentController(DeviceEnrolmentService enrolments) {
        this.enrolments = enrolments;
    }

    @PostMapping("/api/v1/pos/enrolment")
    ResponseEntity<DeviceEnrolmentResponse> enrolTerminal(@Valid @RequestBody DeviceEnrolmentRequest request,
                                                          HttpServletRequest http) {
        return enrol(DeviceKind.POS, request, http);
    }

    @PostMapping("/api/v1/bridge/enrolment")
    ResponseEntity<DeviceEnrolmentResponse> enrolBridge(@Valid @RequestBody DeviceEnrolmentRequest request,
                                                        HttpServletRequest http) {
        return enrol(DeviceKind.PRINT_BRIDGE, request, http);
    }

    private ResponseEntity<DeviceEnrolmentResponse> enrol(DeviceKind kind, DeviceEnrolmentRequest request,
                                                          HttpServletRequest http) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(enrolments.enrol(kind, request.code(), request.publicJwk(), http.getRemoteAddr()));
    }

    @ExceptionHandler({InvalidEnrollmentCodeException.class, InvalidDeviceKeyException.class})
    ResponseEntity<ProblemDetail> invalid(BusinessRuleException e) {
        return coded(HttpStatus.BAD_REQUEST, e);
    }

    @ExceptionHandler(EnrolmentThrottledException.class)
    ResponseEntity<ProblemDetail> throttled(EnrolmentThrottledException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfter().toSeconds()))
                .body(coded(HttpStatus.TOO_MANY_REQUESTS, e).getBody());
    }

    private static ResponseEntity<ProblemDetail> coded(HttpStatus status, BusinessRuleException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        problem.setProperty("code", e.code());
        return ResponseEntity.status(status).body(problem);
    }
}
