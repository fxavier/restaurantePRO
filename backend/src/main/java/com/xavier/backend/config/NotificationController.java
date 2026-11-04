package com.xavier.backend.config;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.shared.notify.ChangeNotifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * D60: the POS change stream ({@code text/event-stream}). The store comes from the session (D39); the caller needs
 * {@code DINING_VIEW} or {@code PRODUCTION_VIEW} in it. Events are {@code {topic, id}} only: the client re-reads
 * through the authorized GET (spec §9). No replay: after a reconnect (the emitter times out after 30 minutes, which
 * also re-authenticates) the client refreshes, and it polls every 15 s while the stream is down.
 */
@RestController
class NotificationController {

    private final AccessPolicy accessPolicy;
    private final ChangeNotifier notifier;

    NotificationController(AccessPolicy accessPolicy, ChangeNotifier notifier) {
        this.accessPolicy = accessPolicy;
        this.notifier = notifier;
    }

    @GetMapping(path = "/api/v1/pos/notifications", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter subscribe() {
        PosContext pos = accessPolicy.requirePosAny(Permission.DINING_VIEW, Permission.PRODUCTION_VIEW);
        return notifier.subscribe(pos.storeId());
    }
}
