package com.gathin.tvhome;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class DysonMqttTest {
    @Test public void decodesKnownMqttFramesAndRejectsOversizedOrTruncatedMessages() throws Exception {
        DysonMqtt.Frame f = DysonMqtt.receive(new DataInputStream(new ByteArrayInputStream(new byte[]{0x20, 2, 0, 0})));
        assertEquals(0x20, f.kind); assertArrayEquals(new byte[]{0, 0}, f.data);
        for (byte[] invalid : new byte[][]{{0x30, 2, 1}, {0x30, (byte)0x81, (byte)0x80, 2}, {0x30, (byte)0x80, (byte)0x80, (byte)0x80, (byte)0x80}}) {
            try { DysonMqtt.receive(new DataInputStream(new ByteArrayInputStream(invalid))); fail("Malformed frame accepted"); }
            catch (IOException expected) { }
        }
    }
    @Test public void packetLengthsUseMqttVariableEncoding() throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DysonMqtt.send(new DataOutputStream(b), 0x30, new byte[130]);
        byte[] data = b.toByteArray();
        assertEquals(0x82, data[1] & 255); assertEquals(1, data[2]); assertEquals(133, data.length);
    }
    @Test public void closedReaderCannotStartANewConnection() throws Exception {
        DysonMqtt reader = new DysonMqtt(); reader.close();
        try { reader.read(new DysonConfig("127.0.0.1", "TEST-SG-12345678", "test")); fail("Closed reader connected"); }
        catch (IOException expected) { assertEquals("Reader closed", expected.getMessage()); }
    }
    @Test public void readsFreshSensorsAndOnlyPublishesTheSensorRequest() throws Exception {
        try (ServerSocket broker = new ServerSocket(1883, 1, InetAddress.getByName("127.0.0.1"))) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<?> simulation = executor.submit(() -> {
                try (Socket client = broker.accept()) {
                    client.setSoTimeout(4000);
                    DataInputStream in = new DataInputStream(client.getInputStream());
                    DataOutputStream out = new DataOutputStream(client.getOutputStream());
                    DysonMqtt.Frame connect = DysonMqtt.receive(in);
                    assertEquals(0x10, connect.kind);
                    assertEquals("MQIsdp", new String(connect.data, 2, 6, StandardCharsets.UTF_8));
                    out.write(new byte[]{0x20, 2, 0, 0}); out.flush();
                    DysonMqtt.Frame subscribe = DysonMqtt.receive(in);
                    assertEquals(0x82, subscribe.kind);
                    // A retained, obsolete value must never appear as a live reading.
                    publish(out, 0x31, 999);
                    out.write(new byte[]{(byte)0x90, 3, 0, 1, 0}); out.flush();
                    DysonMqtt.Frame query = DysonMqtt.receive(in);
                    assertEquals(0x30, query.kind);
                    int topicLength = (query.data[0] & 255) * 256 + (query.data[1] & 255);
                    assertEquals("897/TEST-SG-12345678/command", new String(query.data, 2, topicLength, StandardCharsets.UTF_8));
                    JSONObject request = new JSONObject(new String(query.data, 2 + topicLength, query.data.length - 2 - topicLength, StandardCharsets.UTF_8));
                    assertEquals("REQUEST-PRODUCT-ENVIRONMENT-CURRENT-SENSOR-DATA", request.getString("msg"));
                    assertEquals(2, request.length()); // No settings/control payload.
                    publish(out, 0x31, 888);
                    publish(out, 0x30, 3);
                    assertEquals(0xE0, DysonMqtt.receive(in).kind);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            try {
                DysonReading value = new DysonMqtt().read(new DysonConfig("127.0.0.1", "TEST-SG-12345678", "dummy-password"));
                assertEquals(3, value.pm25); assertEquals(3, value.pm10);
                simulation.get(5, TimeUnit.SECONDS);
            } finally { broker.close(); executor.shutdownNow(); }
        }
    }
    private static void publish(DataOutputStream out, int kind, int value) throws Exception {
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        DysonMqtt.string(message, "897/TEST-SG-12345678/status/current");
        message.write(("{\"msg\":\"ENVIRONMENTAL-CURRENT-SENSOR-DATA\",\"data\":{\"p25r\":\"" + value + "\",\"p10r\":\"" + value + "\"}}").getBytes(StandardCharsets.UTF_8));
        DysonMqtt.send(out, kind, message.toByteArray());
    }
}
