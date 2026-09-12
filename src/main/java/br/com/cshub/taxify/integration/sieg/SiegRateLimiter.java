package br.com.cshub.taxify.integration.sieg;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Simple in-process sliding-window limiter to respect SIEG's published
 * limit of {@link SiegProperties#getMaxRequestsPerMinute()} requests/minute.
 * Blocks the calling thread (briefly) instead of letting the call fail with HTTP 429.
 */
@Component
public class SiegRateLimiter {

    private final SiegProperties properties;
    private final Deque<Long> callTimestamps = new ArrayDeque<>();

    public SiegRateLimiter(SiegProperties properties) {
        this.properties = properties;
    }

    public synchronized void acquire() {
        long windowMillis = 60_000L;
        while (true) {
            long now = System.currentTimeMillis();
            while (!callTimestamps.isEmpty() && now - callTimestamps.peekFirst() >= windowMillis) {
                callTimestamps.pollFirst();
            }
            if (callTimestamps.size() < properties.getMaxRequestsPerMinute()) {
                callTimestamps.addLast(now);
                return;
            }
            long waitMillis = windowMillis - (now - callTimestamps.peekFirst());
            sleep(Math.max(waitMillis, 10));
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for SIEG rate limit window", e);
        }
    }
}
