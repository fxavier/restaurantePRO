package com.xavier.backend.config;

import com.xavier.backend.shared.events.EventPublicationState;
import com.xavier.backend.shared.events.EventPublicationView;
import com.xavier.backend.shared.web.PageResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * D88: incomplete event publications (oldest first, without their payload) and a person's resubmission of them, for
 * {@code SYSTEM_MONITOR}. {@code state=FAILED} lists the ones the automatic job gave up on.
 */
@RestController
@RequestMapping(SystemEventPublicationController.PATH)
class SystemEventPublicationController {

    static final String PATH = "/api/v1/backoffice/system/event-publications";

    private final SystemEventPublications publications;

    SystemEventPublicationController(SystemEventPublications publications) {
        this.publications = publications;
    }

    @GetMapping
    PageResponse<EventPublicationView> list(@RequestParam(defaultValue = "INCOMPLETE") EventPublicationState state,
                                            @PageableDefault(size = 50) Pageable pageable) {
        return publications.list(state, pageable);
    }

    /** 202: the listeners run asynchronously. Repeatable without a key: listeners are idempotent (D19). */
    @PostMapping("/resubmit")
    ResponseEntity<ResubmissionView> resubmit(@Valid @RequestBody ResubmitRequest request) {
        return ResponseEntity.accepted().body(publications.resubmit(request));
    }
}
