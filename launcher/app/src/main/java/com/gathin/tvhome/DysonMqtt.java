package com.gathin.tvhome;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/** Small, bounded MQTT 3.1 reader. The only publish is a current-sensor request. */
final class DysonMqtt implements AutoCloseable {
    private Socket socket;
    private boolean closed;

    private synchronized void attach(Socket value) throws IOException {
        if (closed) { value.close(); throw new IOException("Reader closed"); }
        socket = value;
    }

    DysonReading read(DysonConfig config) throws Exception {
        try (Socket connection = new Socket()) {
            attach(connection);
            connection.connect(new InetSocketAddress(config.host, 1883), 5000);
            connection.setSoTimeout(12000);
            DataInputStream in = new DataInputStream(connection.getInputStream());
            DataOutputStream out = new DataOutputStream(connection.getOutputStream());
            ByteArrayOutputStream connect = new ByteArrayOutputStream();
            string(connect, "MQIsdp");
            connect.write(new byte[]{3, (byte) 0xC2, 0, 30});
            string(connect, "tv" + UUID.randomUUID().toString().replace("-", "").substring(0, 18));
            string(connect, config.serial); string(connect, config.credential);
            send(out, 0x10, connect.toByteArray());
            Frame frame = receive(in);
            if (frame.kind != 0x20 || frame.data.length != 2 || frame.data[1] != 0)
                throw new IOException("Device authentication failed");
            String root = "897/" + config.serial;
            String status = root + "/status/current";
            ByteArrayOutputStream subscribe = new ByteArrayOutputStream();
            subscribe.write(new byte[]{0, 1}); string(subscribe, status); subscribe.write(0);
            send(out, 0x82, subscribe.toByteArray());
            // A broker may send retained messages before SUBACK. Never display those snapshots.
            long deadline = System.nanoTime() + 15_000_000_000L;
            do {
                frame = receive(in);
            } while (frame.kind >> 4 == 3 && System.nanoTime() < deadline);
            if (frame.kind != 0x90 || frame.data.length != 3 || frame.data[0] != 0
                    || frame.data[1] != 1 || frame.data[2] != 0)
                throw new IOException("Sensor subscription failed");
            ByteArrayOutputStream request = new ByteArrayOutputStream();
            string(request, root + "/command");
            request.write(new JSONObject().put("msg", "REQUEST-PRODUCT-ENVIRONMENT-CURRENT-SENSOR-DATA")
                    .put("time", Instant.now().toString()).toString().getBytes(StandardCharsets.UTF_8));
            send(out, 0x30, request.toByteArray());
            while (System.nanoTime() < deadline) {
                frame = receive(in);
                if (frame.kind >> 4 != 3) continue;
                DataInputStream payload = new DataInputStream(new java.io.ByteArrayInputStream(frame.data));
                int topicLength = payload.readUnsignedShort();
                if (topicLength > payload.available()) throw new IOException("Malformed sensor message");
                byte[] topicBytes = new byte[topicLength]; payload.readFully(topicBytes);
                String topic = new String(topicBytes, StandardCharsets.UTF_8);
                int qos = frame.kind >> 1 & 3;
                if (qos > 1) throw new IOException("Unsupported delivery mode");
                if (qos == 1) {
                    int id = payload.readUnsignedShort();
                    send(out, 0x40, new byte[]{(byte) (id >> 8), (byte) id});
                }
                if ((frame.kind & 1) != 0 || !status.equals(topic)) continue;
                byte[] messageBytes = new byte[payload.available()]; payload.readFully(messageBytes);
                JSONObject message = new JSONObject(new String(messageBytes, StandardCharsets.UTF_8));
                // Reject an explicitly old device observation; some firmware omits the timestamp.
                String stamp = message.optString("time");
                if (!stamp.isEmpty()) {
                    long age = System.currentTimeMillis() - Instant.parse(stamp).toEpochMilli();
                    if (age < -120_000L || age > 300_000L) continue;
                }
                DysonReading reading = DysonReading.parse(message);
                if (reading != null) {
                    send(out, 0xE0, new byte[0]);
                    return reading;
                }
            }
            throw new IOException("No current sensor reading");
        } finally { close(); }
    }

    static void string(ByteArrayOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 65535) throw new IOException("Field too large");
        out.write(bytes.length >> 8); out.write(bytes.length & 255); out.write(bytes);
    }

    static void send(DataOutputStream out, int kind, byte[] data) throws IOException {
        out.writeByte(kind);
        int size = data.length;
        do {
            int digit = size % 128; size /= 128;
            out.writeByte(digit | (size > 0 ? 128 : 0));
        } while (size > 0);
        out.write(data); out.flush();
    }

    static Frame receive(DataInputStream in) throws IOException {
        int kind = in.readUnsignedByte(), size = 0;
        for (int i = 0; i < 4; i++) {
            int digit = in.readUnsignedByte();
            size |= (digit & 127) << (7 * i);
            if (size > 32768) throw new IOException("Message too large");
            if ((digit & 128) == 0) {
                byte[] data = new byte[size]; in.readFully(data);
                return new Frame(kind, data);
            }
        }
        throw new IOException("Malformed MQTT length");
    }

    static final class Frame {
        final int kind; final byte[] data;
        Frame(int kind, byte[] data) { this.kind = kind; this.data = data; }
    }

    @Override public synchronized void close() {
        closed = true;
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
        socket = null;
    }
}
