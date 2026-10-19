package dev.atlas.eventing;

public final class RetryPolicy {

    private RetryPolicy() {}

    public static long delay(int attempt) {
        if (attempt < 1 || attempt > 3) throw new IllegalArgumentException(
            "Attempt must be 1–3"
        );
        return 1L << (attempt - 1);
    }
}
