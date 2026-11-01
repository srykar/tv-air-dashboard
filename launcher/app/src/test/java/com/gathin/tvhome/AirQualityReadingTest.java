package com.gathin.tvhome;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class AirQualityReadingTest {
    private JSONObject sample(String readings) throws Exception {
        return new JSONObject("{\"code\":0,\"data\":{\"items\":[{\"timestamp\":\"2026-11-01T00:00:00+08:00\",\"readings\":"
                + readings + "}]}}");
    }
    @Test public void readsSelectedRegionAndItsPublishedTime() throws Exception {
        AirQualityReading r = AirQualityReading.parse(sample("{\"pm25_one_hourly\":{\"north\":26,\"east\":31}}"), "pm25_one_hourly", "north");
        assertEquals(26, r.value);
        assertEquals(31, AirQualityReading.parse(sample("{\"pm25_one_hourly\":{\"north\":26,\"east\":31}}"), "pm25_one_hourly", "east").value);
        assertEquals("00:00", r.timeLabel());
        assertTrue(r.recent(r.publishedAt + 60_000L));
    }
    @Test(expected=Exception.class) public void missingRegionDoesNotFallBackToAnother() throws Exception {
        AirQualityReading.parse(sample("{\"pm25_one_hourly\":{\"east\":31}}"), "pm25_one_hourly", "north");
    }
    @Test(expected=Exception.class) public void errorResponseIsNotAnObservation() throws Exception {
        AirQualityReading.parse(new JSONObject("{\"code\":429,\"data\":null}"), "pm25_one_hourly", "north");
    }
    @Test(expected=Exception.class) public void emptyItemsAreNotAnObservation() throws Exception {
        AirQualityReading.parse(new JSONObject("{\"code\":0,\"data\":{\"items\":[]}}"), "pm25_one_hourly", "north");
    }
    @Test(expected=Exception.class) public void negativeReadingIsRejected() throws Exception {
        AirQualityReading.parse(sample("{\"pm25_one_hourly\":{\"north\":-1}}"), "pm25_one_hourly", "north");
    }
    @Test public void staleAndFarFutureObservationsAreNotShownAsCurrent() {
        AirQualityReading r = new AirQualityReading(63, 2_000_000_000_000L);
        assertTrue(r.recent(r.publishedAt + AirQualityReading.MAX_AGE_MS));
        assertFalse(r.recent(r.publishedAt + AirQualityReading.MAX_AGE_MS + 1));
        assertFalse(r.recent(r.publishedAt - 11 * 60_000L));
    }
    @Test public void psiBandsUsePublishedBoundaryValues() {
        assertEquals("Good", AirQualityReading.psiBand(50));
        assertEquals("Moderate", AirQualityReading.psiBand(51));
        assertEquals("Moderate", AirQualityReading.psiBand(100));
        assertEquals("Unhealthy", AirQualityReading.psiBand(101));
        assertEquals("Very unhealthy", AirQualityReading.psiBand(201));
        assertEquals("Hazardous", AirQualityReading.psiBand(301));
    }
}
