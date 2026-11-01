package com.gathin.tvhome;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class DysonReadingTest {
    private static JSONObject message(String data) throws Exception {
        return new JSONObject("{\"msg\":\"ENVIRONMENTAL-CURRENT-SENSOR-DATA\",\"data\":" + data + "}");
    }
    @Test public void hushjetRawFieldsTakePrecedenceOverLegacyFields() throws Exception {
        DysonReading r = DysonReading.parse(message("{\"p25r\":\"0003\",\"p10r\":\"0004\",\"pm25\":\"0009\",\"pm10\":\"0008\"}"));
        assertEquals(3, r.pm25); assertEquals(4, r.pm10);
    }
    @Test public void legacyFieldsAndZeroAreValid() throws Exception {
        DysonReading r = DysonReading.parse(message("{\"pm25\":\"0000\",\"pm10\":\"0012\"}"));
        assertEquals(0, r.pm25); assertEquals(12, r.pm10);
    }
    @Test public void invalidSensorStatesNeverBecomeZero() throws Exception {
        for (String value : new String[]{"OFF", "INIT", "FAIL", "NONE", "-1", "9999", "3.1", ""}) {
            assertNull(DysonReading.parse(message("{\"p25r\":\"" + value + "\",\"p10r\":\"0003\"}")));
        }
        assertNull(DysonReading.parse(message("{\"p10r\":\"0003\"}")));
        assertNull(DysonReading.parse(new JSONObject("{\"msg\":\"CURRENT-STATE\"}")));
    }
    @Test public void changeArraysUseCurrentValue() throws Exception {
        DysonReading r = DysonReading.parse(message("{\"p25r\":[\"0002\",\"0005\"],\"p10r\":\"0006\"}"));
        assertEquals(5, r.pm25);
    }
    @Test public void setupOnlyAcceptsPrivateHushjetEndpoints() throws Exception {
        for (String host : new String[]{"192.168.1.50", "10.0.0.2", "172.16.0.2", "172.31.255.2"})
            assertEquals(host, DysonConfig.parse(config(host)).host);
        for (String host : new String[]{"8.8.8.8", "127.0.0.1", "172.32.0.2", "192.168.999.1", "example.com", "192.168.1.2/other"}) {
            try { DysonConfig.parse(config(host)); fail("Endpoint accepted"); }
            catch (IllegalArgumentException expected) { }
        }
    }
    static String config(String host) {
        return "{\"host\":\"" + host + "\",\"port\":1883,\"product_type\":\"897\",\"serial\":\"TEST-SG-12345678\",\"credential\":\"AAAAAAAAAAAAAAAAAAAAAAAA\"}";
    }
}
