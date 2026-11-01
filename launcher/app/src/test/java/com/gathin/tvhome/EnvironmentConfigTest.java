package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class EnvironmentConfigTest {
    @Test public void emptyDefaultsNeverEnableWeatherRequests() {
        assertFalse(EnvironmentConfig.weatherEnabled("", ""));
        assertFalse(EnvironmentConfig.weatherEnabled(null, null));
    }
    @Test public void malformedNonfiniteAndOutOfRangeValuesAreRejected() {
        for (String lat : new String[]{"NaN", "Infinity", "91", "-91", "bad", "0&key=extra"}) {
            assertFalse(EnvironmentConfig.weatherEnabled(lat, "0"));
        }
        for (String lon : new String[]{"NaN", "Infinity", "181", "-181", "bad"}) {
            assertFalse(EnvironmentConfig.weatherEnabled("0", lon));
        }
    }
    @Test public void explicitValidCoordinatesEnableWeather() {
        assertTrue(EnvironmentConfig.weatherEnabled("0", "0"));
        assertTrue(EnvironmentConfig.weatherEnabled("-90", "180"));
    }
    @Test public void airRequiresAnExplicitSupportedRegion() {
        assertFalse(EnvironmentConfig.airEnabled(""));
        assertFalse(EnvironmentConfig.airEnabled(null));
        assertFalse(EnvironmentConfig.airEnabled("automatic"));
        for (String region : new String[]{"north", "south", "east", "west", "central"}) {
            assertTrue(EnvironmentConfig.airEnabled(region));
        }
    }
}
