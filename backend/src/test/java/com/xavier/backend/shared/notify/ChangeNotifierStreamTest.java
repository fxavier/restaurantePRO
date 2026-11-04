package com.xavier.backend.shared.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.StoreActivity;
import com.xavier.backend.access.WithActor;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.Filter;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * S7 review follow-ups on the change stream (D60): a timed-out stream is completed, so the servlet container does
 * not turn the timeout into an error dispatch; and the heartbeat is written by the notifier's sender thread, like
 * the notices, never by the scheduler thread (one writer per stream, and a slow client cannot stall the scheduler).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ChangeNotifierStreamTest.Wiring.class})
@WithActor(permissions = Permission.DINING_VIEW, storeId = ChangeNotifierStreamTest.STORE,
        employeeId = "0190e000-0000-7000-8000-0000000000e1", terminalId = "0190f000-0000-7000-8000-0000000000f1")
class ChangeNotifierStreamTest {

    static final String STORE = "0190a000-0000-7000-8000-0000000000b1";
    static final String PATH = "/api/v1/pos/notifications";
    static final Set<String> WRITER_THREADS = ConcurrentHashMap.newKeySet();

    @Autowired
    MockMvc mvc;

    @Autowired
    ChangeNotifier notifier;

    @BeforeEach
    void clear() {
        WRITER_THREADS.clear();
    }

    @Test
    void aTimedOutStreamIsCompletedInsteadOfFailingWithATimeout() throws Exception {
        MvcResult stream = subscribe();

        MockAsyncContext async = (MockAsyncContext) stream.getRequest().getAsyncContext();
        for (AsyncListener listener : async.getListeners()) {
            listener.onTimeout(new AsyncEvent(async));
        }

        // completed by the emitter: the async result is its normal (null) completion, not the timeout error
        Object result = stream.getAsyncResult(Duration.ofSeconds(5).toMillis());
        assertThat(result).as("async result (an AsyncRequestTimeoutException without complete())").isNull();
    }

    @Test
    void theHeartbeatIsWrittenByTheSenderThreadNotTheCaller() throws Exception {
        MvcResult stream = subscribe();

        notifier.heartbeat();

        eventually(() -> assertThat(new String(stream.getResponse().getContentAsByteArray(),
                StandardCharsets.UTF_8)).contains(":heartbeat"));
        assertThat(WRITER_THREADS).contains("change-notifier").doesNotContain(Thread.currentThread().getName());
    }

    private MvcResult subscribe() throws Exception {
        return mvc.perform(get(PATH).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    private static void eventually(Runnable assertion) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (true) {
            try {
                assertion.run();
                return;
            } catch (AssertionError e) {
                if (System.nanoTime() > deadline) {
                    throw e;
                }
                Thread.sleep(20);
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Wiring {

        @Bean
        @Primary
        StoreActivity allStoresActive() {
            return storeId -> true;
        }

        /** Records which thread writes the stream's bytes. */
        @Bean
        Filter writerThreadRecorder() {
            return (request, response, chain) -> chain.doFilter(request,
                    new HttpServletResponseWrapper((HttpServletResponse) response) {
                        @Override
                        public ServletOutputStream getOutputStream() throws IOException {
                            ServletOutputStream out = super.getOutputStream();
                            return new ServletOutputStream() {
                                @Override
                                public void write(int b) throws IOException {
                                    WRITER_THREADS.add(Thread.currentThread().getName());
                                    out.write(b);
                                }

                                @Override
                                public void write(byte[] b, int off, int len) throws IOException {
                                    WRITER_THREADS.add(Thread.currentThread().getName());
                                    out.write(b, off, len);
                                }

                                @Override
                                public void flush() throws IOException {
                                    out.flush();
                                }

                                @Override
                                public boolean isReady() {
                                    return out.isReady();
                                }

                                @Override
                                public void setWriteListener(WriteListener listener) {
                                    out.setWriteListener(listener);
                                }
                            };
                        }
                    });
        }
    }
}
