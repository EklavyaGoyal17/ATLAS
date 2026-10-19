package dev.atlas.platform;

import org.springframework.stereotype.Component;

/** One local bearer identity: process-wide token bucket, 120 burst, 60 requests/second. */
@Component
public class RequestBudget {

    private double tokens = 120;
    private long last = System.nanoTime();

    public synchronized boolean allow() {
        return allow(System.nanoTime());
    }

    synchronized boolean allow(long now) {
        tokens = Math.min(120, tokens + (Math.max(0, now - last) / 1e9) * 60);
        last = now;
        if (tokens < 1) return false;
        tokens -= 1;
        return true;
    }
}
