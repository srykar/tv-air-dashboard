package com.gathin.tvhome;

import org.json.JSONObject;

final class DysonConfig {
    static final String FILE = "dyson-connection.json";
    final String host, serial, credential;
    DysonConfig(String host, String serial, String credential) {
        this.host = host; this.serial = serial; this.credential = credential;
    }

    static DysonConfig parse(String text) throws Exception {
        if (text.length() > 4096) throw new IllegalArgumentException("Invalid device setup");
        JSONObject json = new JSONObject(text);
        String host = json.getString("host");
        String[] octets = host.split("\\.", -1);
        if (octets.length != 4) throw new IllegalArgumentException("Local address required");
        int[] address = new int[4];
        for (int i = 0; i < 4; i++) {
            if (!octets[i].matches("[0-9]{1,3}")) throw new IllegalArgumentException("Invalid address");
            address[i] = Integer.parseInt(octets[i]);
            if (address[i] > 255) throw new IllegalArgumentException("Invalid address");
        }
        if (!(address[0] == 10 || address[0] == 192 && address[1] == 168
                || address[0] == 172 && address[1] >= 16 && address[1] <= 31)) {
            throw new IllegalArgumentException("Local address required");
        }
        if (!"897".equals(json.getString("product_type")) || json.optInt("port", 1883) != 1883)
            throw new IllegalArgumentException("HushJet setup required");
        String serial = json.getString("serial"), credential = json.getString("credential");
        if (!serial.matches("[A-Z0-9-]{8,40}") || !credential.matches("[A-Za-z0-9+/=]{16,256}"))
            throw new IllegalArgumentException("Invalid device setup");
        return new DysonConfig(host, serial, credential);
    }
}
