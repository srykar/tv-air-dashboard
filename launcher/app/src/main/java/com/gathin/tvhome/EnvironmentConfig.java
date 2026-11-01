package com.gathin.tvhome;

/** Explicit builder-selected sources; no bundled household or fallback location. */
final class EnvironmentConfig {
    static boolean weatherEnabled(String latitude, String longitude) {
        try {
            double lat = Double.parseDouble(latitude), lon = Double.parseDouble(longitude);
            return Double.isFinite(lat) && Double.isFinite(lon)
                    && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
        } catch (NumberFormatException | NullPointerException invalid) { return false; }
    }

    static boolean airEnabled(String region) {
        return "north".equals(region) || "south".equals(region) || "east".equals(region)
                || "west".equals(region) || "central".equals(region);
    }
}
