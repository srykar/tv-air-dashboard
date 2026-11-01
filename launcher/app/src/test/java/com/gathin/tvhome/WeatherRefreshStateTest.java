package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class WeatherRefreshStateTest {
    private static final long NOW = 1_800_000_000_000L;

    @Test public void firstVisitRefreshesImmediately() {
        assertEquals(0, new WeatherRefreshState(0, false).delay(NOW));
    }

    @Test public void leavingDuringFirstRequestDoesNotDelayTheNextVisit() {
        WeatherRefreshState state = new WeatherRefreshState(0, false);
        state.begin();
        assertTrue(state.loading());
        state.cancel();
        assertFalse(state.loading());
        assertEquals(0, state.delay(NOW + 1_000));
    }

    @Test public void interruptedRefreshDoesNotReplaceThePreviousCompletion() {
        WeatherRefreshState state = new WeatherRefreshState(NOW - WeatherRefreshState.FRESH_MS, true);
        state.begin();
        state.cancel();
        assertEquals(0, state.delay(NOW + 1_000));
    }

    @Test public void actualFailureUsesRetryBackoffFromCompletion() {
        WeatherRefreshState state = new WeatherRefreshState(0, false);
        state.begin();
        state.complete(NOW, false);
        assertFalse(state.loading());
        assertEquals(WeatherRefreshState.RETRY_MS - 1_000, state.delay(NOW + 1_000));
        assertEquals(0, state.delay(NOW + WeatherRefreshState.RETRY_MS));
    }

    @Test public void actualSuccessUsesNormalRefreshInterval() {
        WeatherRefreshState state = new WeatherRefreshState(0, false);
        state.begin();
        state.complete(NOW, true);
        assertFalse(state.loading());
        assertEquals(WeatherRefreshState.FRESH_MS - 1_000, state.delay(NOW + 1_000));
        assertEquals(0, state.delay(NOW + WeatherRefreshState.FRESH_MS));
    }

    @Test public void movingClockBackDoesNotSuppressRefresh() {
        assertEquals(0, new WeatherRefreshState(NOW, true).delay(NOW - 1_000));
    }
}
