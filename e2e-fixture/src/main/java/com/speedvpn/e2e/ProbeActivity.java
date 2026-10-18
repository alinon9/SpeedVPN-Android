package com.speedvpn.e2e;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.Arrays;
import java.util.Locale;

public final class ProbeActivity extends Activity {
    private static final String TAG = "SpeedVPN-Probe";
    private static final String FIXTURE_URL = "http://10.0.2.2:18765/__down?bytes=";
    private static final String FIXTURE_UDP_HOST = "10.0.2.2";
    private static final int FIXTURE_UDP_PORT = 18766;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        final String probeId = getIntent().getStringExtra("probe_id") == null
                ? "missing-id" : getIntent().getStringExtra("probe_id");
        final String protocol = getIntent().getStringExtra("probe_protocol") == null
                ? "tcp" : getIntent().getStringExtra("probe_protocol").toLowerCase(Locale.ROOT);
        final int expectedBytes = Math.max(1, getIntent().getIntExtra("bytes", 4096));

        Thread worker = new Thread(() -> {
            HttpURLConnection connection = null;
            long receivedBytes = 0L;
            int httpStatus = -1;
            boolean passed = false;
            String detail = "unknown";
            try {
                if ("udp".equals(protocol)) {
                    if (expectedBytes > 60_000) {
                        throw new IllegalArgumentException("UDP fixture payload must not exceed 60000 bytes");
                    }
                    byte[] payload = new byte[expectedBytes];
                    for (int i = 0; i < payload.length; i++) {
                        payload[i] = (byte) (i * 31 + 7);
                    }
                    byte[] responseBytes = new byte[expectedBytes];
                    try (DatagramSocket socket = new DatagramSocket()) {
                        socket.setSoTimeout(3000);
                        InetSocketAddress destination = new InetSocketAddress(
                                InetAddress.getByName(FIXTURE_UDP_HOST), FIXTURE_UDP_PORT);
                        socket.send(new DatagramPacket(payload, payload.length, destination));
                        DatagramPacket response = new DatagramPacket(responseBytes, responseBytes.length);
                        socket.receive(response);
                        receivedBytes = response.getLength();
                        passed = receivedBytes == expectedBytes
                                && Arrays.equals(payload, Arrays.copyOf(responseBytes, response.getLength()));
                        detail = passed ? "complete" : "udp-payload-mismatch";
                    }
                } else {
                    connection = (HttpURLConnection) new URL(FIXTURE_URL + expectedBytes).openConnection();
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(3000);
                    connection.setReadTimeout(60_000);
                    connection.setUseCaches(false);
                    connection.setRequestProperty("Cache-Control", "no-cache, no-store");
                    httpStatus = connection.getResponseCode();
                    if (httpStatus != HttpURLConnection.HTTP_OK) {
                        detail = "http-status";
                    } else {
                        try (InputStream input = connection.getInputStream()) {
                            byte[] buffer = new byte[16 * 1024];
                            int read;
                            while ((read = input.read(buffer)) != -1) receivedBytes += read;
                        }
                        passed = receivedBytes == expectedBytes;
                        detail = passed ? "complete" : "truncated";
                    }
                }
            } catch (Exception error) {
                detail = error.getClass().getSimpleName() + ":" + String.valueOf(error.getMessage());
            } finally {
                if (connection != null) connection.disconnect();
                Log.i(TAG, "E2E_PROBE id=" + probeId
                        + " package=" + getPackageName()
                        + " protocol=" + protocol.toUpperCase(Locale.ROOT)
                        + " result=" + (passed ? "PASS" : "FAIL")
                        + " http=" + httpStatus
                        + " received=" + receivedBytes
                        + " expected=" + expectedBytes
                        + " detail=" + detail);
                runOnUiThread(this::finish);
            }
        }, "speedvpn-e2e-probe");
        worker.start();
    }
}
