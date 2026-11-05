package com.xavier.backend.settlement;

import com.xavier.backend.shared.ExternalCalls;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * The integrated provider of the tests (D53: R2 ships no real one), code {@code FAKE}. It keeps what it "really" did
 * per reference, so a status query after a timeout tells the truth (a reference it never received is
 * {@code NotFound}, D81), and counts the charges per reference. Like every
 * adapter it refuses to run inside a transaction. {@link Behaviour#HANG} takes the money but answers only after
 * {@link #HANG}, longer than the test provider timeout (PT2S), so settlement sees a timeout.
 */
public class FakePaymentProvider implements PaymentProvider {

    public static final String CODE = "FAKE";
    static final Duration HANG = Duration.ofSeconds(5);

    /**
     * {@code DELAYED}: the charge reaches the provider only when the test calls {@link #release()} (at most 1.5 s, below
     * the provider timeout), then succeeds; until then the provider does not know the reference.
     */
    public enum Behaviour { SUCCEED, DECLINE, HANG, PENDING, DELAYED }

    private volatile CountDownLatch delayed = new CountDownLatch(1);

    private volatile Behaviour next = Behaviour.SUCCEED;
    private final Map<String, ProviderOutcome> truth = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> charges = new ConcurrentHashMap<>();

    /** How every following charge behaves (until changed). */
    public void willNext(Behaviour behaviour) {
        next = behaviour;
    }

    /** The provider decides a PENDING charge later. */
    public void decide(String reference, ProviderOutcome outcome) {
        truth.put(reference, outcome);
    }

    /** The provider loses every trace of the reference: a status query answers {@code NotFound} (D81). */
    public void forget(String reference) {
        truth.remove(reference);
    }

    /** Lets a DELAYED charge reach the provider. */
    public void release() {
        delayed.countDown();
    }

    /** Re-arms the DELAYED gate (call before each test that uses it). */
    public void arm() {
        delayed = new CountDownLatch(1);
    }

    public int charges(String reference) {
        AtomicInteger count = charges.get(reference);
        return count == null ? 0 : count.get();
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public ProviderOutcome charge(ChargeRequest request) {
        ExternalCalls.requireNoTransaction();
        String reference = request.reference();
        charges.computeIfAbsent(reference, r -> new AtomicInteger()).incrementAndGet();
        ProviderOutcome taken = new ProviderOutcome.Succeeded("FAKE-" + reference);
        switch (next) {
            case SUCCEED -> truth.put(reference, taken);
            case DECLINE -> truth.put(reference, new ProviderOutcome.Failed("declined by the fake"));
            case PENDING -> truth.put(reference, new ProviderOutcome.Pending());
            case DELAYED -> {
                try {
                    delayed.await(1500, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                truth.put(reference, taken);
            }
            case HANG -> {
                truth.put(reference, taken);
                try {
                    Thread.sleep(HANG);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        return truth.get(reference);
    }

    @Override
    public ProviderOutcome status(String reference) {
        ExternalCalls.requireNoTransaction();
        return truth.getOrDefault(reference, new ProviderOutcome.NotFound());
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {

        @Bean
        FakePaymentProvider fakePaymentProvider() {
            return new FakePaymentProvider();
        }
    }
}
