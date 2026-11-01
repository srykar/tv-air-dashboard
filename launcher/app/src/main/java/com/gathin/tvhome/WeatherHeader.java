package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Optional configured sources. Network requests and refresh timers run only while Home is visible. */
final class WeatherHeader {
    private static final long FRESH_MS = WeatherRefreshState.FRESH_MS;
    private static final long RETRY_MS = WeatherRefreshState.RETRY_MS;
    private static final long CACHE_MS = 2 * 60 * 60_000L;
    private final TextView view;
    private final TextView air;
    private final TextView aqiValue, pm25Value, pm10Value;
    private final SharedPreferences prefs;
    private final String endpoint;
    private final String region;
    private final String regionLabel;
    private final boolean weatherConfigured, airConfigured;
    private final WeatherRefreshState refreshState;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final Runnable refresh = this::refresh;
    private volatile int generation;
    private volatile boolean active;
    private Future<?> pending;

    WeatherHeader(Context context, TextView view, TextView air, TextView aqiValue, TextView pm25Value, TextView pm10Value) {
        this.view = view;
        this.air = air;
        this.aqiValue = aqiValue;
        this.pm25Value = pm25Value;
        this.pm10Value = pm10Value;
        prefs = context.getSharedPreferences("personal_weather", Context.MODE_PRIVATE);
        region = context.getString(R.string.haze_region);
        regionLabel = context.getString(R.string.haze_region_label);
        String latitude = context.getString(R.string.weather_latitude);
        String longitude = context.getString(R.string.weather_longitude);
        weatherConfigured = EnvironmentConfig.weatherEnabled(latitude, longitude);
        airConfigured = EnvironmentConfig.airEnabled(region);
        endpoint = "https://api.open-meteo.com/v1/forecast?latitude="
                + (weatherConfigured ? Double.toString(Double.parseDouble(latitude)) : "") + "&longitude="
                + (weatherConfigured ? Double.toString(Double.parseDouble(longitude)) : "")
                + "&current=temperature_2m,weather_code&timezone=UTC";
        String locationKey = endpoint + "|" + region + "|particle-24h-v1";
        if (!locationKey.equals(prefs.getString("source_key", ""))) {
            prefs.edit().clear().putString("source_key", locationKey).apply();
        }
        refreshState = new WeatherRefreshState(prefs.getLong("completed_at", 0),
                prefs.getBoolean("all_ok", false));
        render(false);
    }

    void start() {
        if (active) return;
        active = true;
        render(false);
        if (!weatherConfigured && !airConfigured) return;
        long delay = refreshState.delay(System.currentTimeMillis());
        if (delay > 0) main.postDelayed(refresh, delay);
        else refresh();
    }

    void stop() {
        active = false;
        generation++;
        main.removeCallbacks(refresh);
        if (pending != null) pending.cancel(true);
        pending = null;
        refreshState.cancel();
    }

    void close() {
        stop();
        network.shutdownNow();
    }

    private AirQualityReading cached(String name) {
        int value = prefs.getInt(name + "_value", -1);
        if (value < 0) return null;
        AirQualityReading reading = new AirQualityReading(value, prefs.getLong(name + "_time", 0));
        return reading.recent(System.currentTimeMillis()) ? reading : null;
    }

    private void render(boolean offline) {
        offline = offline || !prefs.getBoolean("weather_ok", true);
        long age = System.currentTimeMillis() - prefs.getLong("fetched_at", 0);
        String reading = prefs.getString("reading", "");
        if (!weatherConfigured) {
            view.setText("Set weather location");
        } else if (!reading.isEmpty() && age >= 0 && age <= CACHE_MS) {
            view.setText(reading + (offline || age >= FRESH_MS ? " · cached" : ""));
        } else {
            view.setText(!offline && refreshState.loading() ? "Updating weather…" : "Weather unavailable");
        }
        AirQualityReading fine = cached("pm25_daily"), coarse = cached("pm10_daily");
        AirMetricText.set(pm25Value, fine == null ? "—" : Integer.toString(fine.value), "µg/m³");
        AirMetricText.set(pm10Value, coarse == null ? "—" : Integer.toString(coarse.value), "µg/m³");
        boolean complete = fine != null && coarse != null && fine.publishedAt == coarse.publishedAt;
        if (complete) {
            int aqi = ParticleAqi.combined(fine.value, coarse.value);
            AirMetricText.set(aqiValue, aqi > 500 ? "500+" : Integer.toString(aqi), "US PM");
            aqiValue.setTextColor(aqi <= 50 ? 0xFF8DDBA1 : aqi <= 100 ? 0xFFFFD578
                    : aqi <= 150 ? 0xFFFFAC6C : aqi <= 200 ? 0xFFFF8484 : 0xFFDAA4E8);
        } else {
            AirMetricText.set(aqiValue, "—", "US PM");
            aqiValue.setTextColor(0xFFFFFFFF);
        }
        AirQualityReading latest = fine != null ? fine : coarse;
        air.setText("Outdoor · " + regionLabel + " · NEA · 24h · "
                + (latest == null ? "Updating…" : latest.timeLabel()));
        if (!airConfigured) air.setText("Outdoor · Choose regional air source");
        aqiValue.setContentDescription("US particle AQI, calculated from 24-hour PM2.5 and PM10 observations: " + aqiValue.getText());
    }

    private boolean current(int request) { return active && request == generation; }

    private void refresh() {
        if (!active) return;
        main.removeCallbacks(refresh);
        final int request = ++generation;
        refreshState.begin();
        render(false);
        pending = network.submit(() -> {
            boolean weatherOk = !weatherConfigured, psiOk = !airConfigured, pmOk = !airConfigured;
            if (weatherConfigured) try {
                if (!current(request)) return;
                JSONObject weather = readJson(endpoint).getJSONObject("current");
                double temperature = weather.getDouble("temperature_2m");
                if (Double.isNaN(temperature) || Double.isInfinite(temperature)
                        || temperature < -90 || temperature > 65) throw new IllegalArgumentException("Invalid weather");
                final String text = String.format(Locale.ENGLISH, "%d°C · %s", Math.round(temperature),
                        conditions(weather.getInt("weather_code")));
                weatherOk = true;
                main.post(() -> {
                    if (!current(request)) return;
                    prefs.edit().putString("reading", text).putLong("fetched_at", System.currentTimeMillis()).putBoolean("weather_ok", true).apply();
                    render(false);
                });
            } catch (Exception unavailable) {
                main.post(() -> { if (current(request)) { prefs.edit().putBoolean("weather_ok", false).apply(); render(true); } });
            }
            if (airConfigured) try {
                if (!current(request)) return;
                JSONObject response = readJson("https://api-open.data.gov.sg/v2/real-time/api/psi");
                AirQualityReading fine = AirQualityReading.parse(response, "pm25_twenty_four_hourly", region);
                AirQualityReading coarse = AirQualityReading.parse(response, "pm10_twenty_four_hourly", region);
                psiOk = fine.recent(System.currentTimeMillis());
                pmOk = coarse.recent(System.currentTimeMillis());
                publish(request, "pm25_daily", fine);
                publish(request, "pm10_daily", coarse);
            } catch (Exception unavailable) { /* Missing readings remain blank; no substitute values. */ }
            final boolean complete = weatherOk && psiOk && pmOk;
            main.post(() -> {
                if (!current(request)) return;
                pending = null;
                long completedAt = System.currentTimeMillis();
                refreshState.complete(completedAt, complete);
                prefs.edit().putLong("completed_at", completedAt).putBoolean("all_ok", complete).apply();
                render(false);
                main.postDelayed(refresh, complete ? FRESH_MS : RETRY_MS);
            });
        });
    }

    private void publish(int request, String name, AirQualityReading reading) {
        main.post(() -> {
            if (!current(request)) return;
            prefs.edit().putInt(name + "_value", reading.value).putLong(name + "_time", reading.publishedAt).apply();
            render(false);
        });
    }

    private JSONObject readJson(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(6_000);
        connection.setReadTimeout(6_000);
        connection.setRequestProperty("User-Agent", "PersonalTVWeather/1.0");
        connection.setRequestProperty("Accept", "application/json");
        try {
            if (connection.getResponseCode() != 200) throw new IllegalStateException("Data unavailable");
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                byte[] chunk = new byte[2048];
                int count;
                while ((count = input.read(chunk)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    if (body.size() + count > 32768) throw new IllegalStateException("Unexpected response size");
                    body.write(chunk, 0, count);
                }
            }
            return new JSONObject(new String(body.toByteArray(), StandardCharsets.UTF_8));
        } finally { connection.disconnect(); }
    }

    static String conditions(int code) {
        if (code == 0) return "Clear";
        if (code == 1 || code == 2) return "Partly cloudy";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code == 85 || code == 86) return "Snow showers";
        if (code >= 95 && code <= 99) return "Thunderstorms";
        return "Current weather";
    }
}
