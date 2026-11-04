package com.xavier.backend.dining;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import com.xavier.backend.shared.notify.ChangeNotifier;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * A store's change stream for dining tests. The real endpoint lives in {@code config}, which a dining module test
 * does not boot, so this throw-away controller subscribes to the same {@link ChangeNotifier}.
 */
public final class DiningChangeStream {

    static final String PATH = "/test/dining/changes/";

    private final MockHttpServletResponse response;

    private DiningChangeStream(MockHttpServletResponse response) {
        this.response = response;
    }

    public static DiningChangeStream subscribe(MockMvc mvc, UUID store) throws Exception {
        return new DiningChangeStream(mvc.perform(get(PATH + store).with(DiningActors.admin()))
                .andExpect(request().asyncStarted()).andReturn().getResponse());
    }

    public String received() {
        return new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    /** Notices are sent off the committing thread; poll briefly (no Awaitility on the classpath). */
    public void awaitTables(UUID id) throws InterruptedException {
        String expected = "{\"topic\":\"TABLES\",\"id\":\"" + id + "\"}";
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!received().contains(expected)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("No TABLES notice for " + id + " in: " + received());
            }
            Thread.sleep(20);
        }
    }

    @RestController
    public static class Probe {

        private final ChangeNotifier notifier;

        Probe(ChangeNotifier notifier) {
            this.notifier = notifier;
        }

        @GetMapping(PATH + "{store}")
        SseEmitter changes(@PathVariable UUID store) {
            return notifier.subscribe(store);
        }
    }
}
