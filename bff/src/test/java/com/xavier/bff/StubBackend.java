package com.xavier.bff;

import java.util.List;
import java.util.Map;

/** The restaurantPRO backend, stubbed: answers every call with the programmed response. */
final class StubBackend extends StubHttpServer {

    private volatile Response next = ok();

    static Response ok() {
        return new Response(200, Map.of("Content-Type", "application/json"), "{\"ok\":true}");
    }

    void willRespond(int status, Map<String, String> headers, String body) {
        next = new Response(status, headers, body);
    }

    void dropNextConnection() {
        next = NO_ANSWER;
    }

    void reset() {
        next = ok();
        clearReceived();
    }

    RecordedRequest onlyRequest() {
        List<RecordedRequest> received = received();
        if (received.size() != 1) {
            throw new AssertionError("expected exactly one backend call, got " + received.size());
        }
        return received.getFirst();
    }

    @Override
    protected Response respond(RecordedRequest request) {
        return next;
    }
}
