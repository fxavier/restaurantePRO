package org.springframework.web.servlet.mvc.method.annotation;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;

/**
 * Test-only: reads what an {@link SseEmitter} sends without an HTTP response, by attaching the handler a servlet
 * response would. It lives in the emitter's package because {@code ResponseBodyEmitter.Handler} is package-private.
 */
public final class EmitterCapture {

    private final List<String> sent = new CopyOnWriteArrayList<>();

    private EmitterCapture() {
    }

    /** Captures everything {@code emitter} sends from now on (its early sends included). */
    public static EmitterCapture of(ResponseBodyEmitter emitter) {
        EmitterCapture capture = new EmitterCapture();
        try {
            emitter.initialize(capture.new Recorder());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        return capture;
    }

    /** Every piece sent, in order, as text. */
    public String text() {
        return String.join("", sent);
    }

    private final class Recorder implements ResponseBodyEmitter.Handler {

        @Override
        public void send(Object data, @Nullable MediaType mediaType) {
            sent.add(String.valueOf(data));
        }

        @Override
        public void send(Set<ResponseBodyEmitter.DataWithMediaType> items) {
            items.forEach(item -> sent.add(String.valueOf(item.getData())));
        }

        @Override
        public void complete() {
            // nothing to flush
        }

        @Override
        public void completeWithError(Throwable failure) {
            // nothing to flush
        }

        @Override
        public void onTimeout(Runnable callback) {
            // never times out in a test
        }

        @Override
        public void onError(Consumer<Throwable> callback) {
            // no I/O, no error
        }

        @Override
        public void onCompletion(Runnable callback) {
            // completion is the test's
        }
    }
}
