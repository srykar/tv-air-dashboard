package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Direct, read-only Dyson polling, only while the home screen is visible. */
final class DysonHeader {
    private final View panel;
    private final TextView fine, coarse, caption;
    private final File configFile;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable refresh = this::refresh;
    private DysonMqtt reader;
    private Future<?> pending;
    private int generation;
    private boolean active;

    DysonHeader(Context context, View panel, TextView fine, TextView coarse, TextView caption) {
        this.panel = panel; this.fine = fine; this.coarse = coarse; this.caption = caption;
        configFile = new File(context.getFilesDir(), DysonConfig.FILE);
    }

    void start() {
        if (active || panel == null) return;
        active = true;
        panel.setVisibility(configFile.isFile() ? View.VISIBLE : View.GONE);
        if (configFile.isFile()) refresh();
    }

    void stop() {
        active = false; generation++;
        main.removeCallbacks(refresh);
        if (reader != null) reader.close();
        if (pending != null) pending.cancel(true);
        reader = null; pending = null;
    }

    void close() { stop(); worker.shutdownNow(); }

    private void refresh() {
        if (!active) return;
        final int request = ++generation;
        final DysonMqtt current = new DysonMqtt();
        reader = current;
        AirMetricText.set(fine, "—", "µg/m³"); AirMetricText.set(coarse, "—", "µg/m³");
        caption.setText("Indoor · Dyson · Connecting…");
        pending = worker.submit(() -> {
            DysonReading value = null;
            try {
                if (configFile.length() > 4096) throw new IllegalArgumentException();
                DysonConfig config = DysonConfig.parse(new String(Files.readAllBytes(configFile.toPath()), StandardCharsets.UTF_8));
                value = current.read(config);
            } catch (Exception unavailable) { /* No identifiers or credentials in logs. */ }
            finally { current.close(); }
            final DysonReading result = value;
            main.post(() -> {
                if (!active || request != generation) return;
                AirMetricText.set(fine, result == null ? "—" : Integer.toString(result.pm25), "µg/m³");
                AirMetricText.set(coarse, result == null ? "—" : Integer.toString(result.pm10), "µg/m³");
                String time = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
                caption.setText("Indoor · Dyson · " + (result == null ? "Unavailable" : "Live " + time));
                main.postDelayed(refresh, 60_000L);
            });
        });
    }
}
