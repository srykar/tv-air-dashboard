package com.gathin.tvhome;

/** A cancelled Home-screen request must not start the completed-request backoff. */
final class WeatherRefreshState {
    static final long FRESH_MS = 30 * 60_000L;
    static final long RETRY_MS = 5 * 60_000L;
    private long completedAt;
    private boolean successful;
    private boolean loading;

    WeatherRefreshState(long completedAt, boolean successful) {
        this.completedAt = completedAt;
        this.successful = successful;
    }

    long delay(long now) {
        long age = now - completedAt;
        long interval = successful ? FRESH_MS : RETRY_MS;
        return completedAt > 0 && age >= 0 && age < interval ? interval - age : 0;
    }

    void begin() { loading = true; }
    void cancel() { loading = false; }
    boolean loading() { return loading; }
    void complete(long now, boolean success) {
        loading = false;
        completedAt = now;
        successful = success;
    }
}
