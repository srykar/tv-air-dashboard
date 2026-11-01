package com.gathin.tvhome;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** US EPA 2024/2026 particle sub-index breakpoints; inputs MUST be 24-hour concentrations.
 * This is the maximum of PM2.5 and PM10 sub-indices, not an all-pollutant or IQAir AQI.
 */
final class ParticleAqi {
    private static final int[] INDEX_LOW = {0, 51, 101, 151, 201, 301};
    private static final int[] INDEX_HIGH = {50, 100, 150, 200, 300, 500};
    private static final double[] FINE_LOW = {0, 9.1, 35.5, 55.5, 125.5, 225.5};
    private static final double[] FINE_HIGH = {9, 35.4, 55.4, 125.4, 225.4, 325.4};
    private static final double[] COARSE_LOW = {0, 55, 155, 255, 355, 425};
    private static final double[] COARSE_HIGH = {54, 154, 254, 354, 424, 604};

    static int fine(double value) { return index(truncate(value, 1), FINE_LOW, FINE_HIGH); }
    static int coarse(double value) { return index(truncate(value, 0), COARSE_LOW, COARSE_HIGH); }
    static int combined(double fine24h, double coarse24h) { return Math.max(fine(fine24h), coarse(coarse24h)); }

    private static double truncate(double value, int places) {
        if (!Double.isFinite(value) || value < 0 || value > 2000) throw new IllegalArgumentException("Invalid particle concentration");
        return BigDecimal.valueOf(value).setScale(places, RoundingMode.DOWN).doubleValue();
    }
    private static int index(double concentration, double[] low, double[] high) {
        int band = 0;
        while (band < high.length - 1 && concentration > high[band]) band++;
        double result = (INDEX_HIGH[band] - INDEX_LOW[band]) / (high[band] - low[band])
                * (concentration - low[band]) + INDEX_LOW[band];
        return (int) Math.round(result);
    }
}
