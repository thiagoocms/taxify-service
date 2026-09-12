package br.com.cshub.taxify.integration.sieg;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SiegRateLimiterTest {

    @Test
    @Timeout(5)
    void acquireReturnsImmediatelyWhileUnderTheLimit() {
        SiegProperties properties = new SiegProperties();
        properties.setMaxRequestsPerMinute(5);
        SiegRateLimiter limiter = new SiegRateLimiter(properties);

        long start = System.currentTimeMillis();
        for (int i = 0; i < 5; i++) {
            limiter.acquire();
        }
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isLessThan(1000);
    }

    @Test
    @Timeout(5)
    void acquireBlocksTheCallerOnceTheWindowIsFull() throws Exception {
        SiegProperties properties = new SiegProperties();
        properties.setMaxRequestsPerMinute(1);
        SiegRateLimiter limiter = new SiegRateLimiter(properties);
        limiter.acquire();

        AtomicReference<Thread> blockedThread = new AtomicReference<>();
        CompletableFuture<Void> secondCall = CompletableFuture.runAsync(() -> {
            blockedThread.set(Thread.currentThread());
            limiter.acquire();
        });

        // The window is 60s and only one slot is allowed: the second call must still
        // be blocked shortly after starting.
        Thread.sleep(200);
        assertThat(secondCall).isNotDone();

        blockedThread.get().interrupt();

        assertThatFutureFailsWithIllegalState(secondCall);
    }

    private void assertThatFutureFailsWithIllegalState(CompletableFuture<Void> future) throws Exception {
        try {
            future.get(2, TimeUnit.SECONDS);
            throw new AssertionError("Expected the interrupted acquire() call to fail");
        } catch (java.util.concurrent.ExecutionException e) {
            assertThat(e.getCause()).isInstanceOf(IllegalStateException.class);
        }
    }
}
