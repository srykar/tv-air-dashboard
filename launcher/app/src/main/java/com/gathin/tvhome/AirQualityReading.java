package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** A published NEA regional observation, not a measurement at the configured home address. */
final class AirQualityReading {
    static final long MAX_AGE_MS = 3 * 60 * 60_000L;
    final int value;
    final long publishedAt;

    AirQualityReading(int value, long publishedAt) {
        if (value < 0 || value > 2000) throw new IllegalArgumentException("Invalid air-quality reading");
        this.value = value;
        this.publishedAt = publishedAt;
    }

    static AirQualityReading parse(JSONObject response, String metric, String region) throws Exception {
        if (response.getInt("code") != 0) throw new IllegalArgumentException("Air-quality API error");
        JSONArray items = response.getJSONObject("data").getJSONArray("items");
        if (items.length() == 0) throw new IllegalArgumentException("No observations");
        JSONObject latest = items.getJSONObject(0);
        int value = latest.getJSONObject("readings").getJSONObject(metric).getInt(region);
        long time = OffsetDateTime.parse(latest.getString("timestamp")).toInstant().toEpochMilli();
        return new AirQualityReading(value, time);
    }

    boolean recent(long now) {
        long age = now - publishedAt;
        return age >= -10 * 60_000L && age <= MAX_AGE_MS;
    }

    String timeLabel() {
        return DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Asia/Singapore"))
                .format(java.time.Instant.ofEpochMilli(publishedAt));
    }

    static String psiBand(int value) {
        if (value <= 50) return "Good";
        if (value <= 100) return "Moderate";
        if (value <= 200) return "Unhealthy";
        if (value <= 300) return "Very unhealthy";
        return "Hazardous";
    }
}
