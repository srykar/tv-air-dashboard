package com.gathin.tvhome;

import org.json.JSONObject;

/** HushJet instantaneous concentrations. These are not a daily AQI. */
final class DysonReading {
    final int pm25, pm10;
    DysonReading(int pm25, int pm10) { this.pm25 = pm25; this.pm10 = pm10; }

    static DysonReading parse(JSONObject message) {
        if (!"ENVIRONMENTAL-CURRENT-SENSOR-DATA".equals(message.optString("msg"))) return null;
        JSONObject data = message.optJSONObject("data");
        if (data == null) return null;
        int fine = value(data, "p25r", "pm25"), coarse = value(data, "p10r", "pm10");
        return fine < 0 || coarse < 0 ? null : new DysonReading(fine, coarse);
    }

    private static int value(JSONObject data, String name, String fallback) {
        Object raw = data.opt(name);
        if (raw == null || raw == JSONObject.NULL) raw = data.opt(fallback);
        if (raw instanceof org.json.JSONArray) {
            org.json.JSONArray values = (org.json.JSONArray) raw;
            raw = values.opt(values.length() - 1);
        }
        if (!(raw instanceof String) || !((String) raw).matches("[0-9]{1,4}")) return -1;
        int value = Integer.parseInt((String) raw);
        return value <= 2000 ? value : -1;
    }
}
