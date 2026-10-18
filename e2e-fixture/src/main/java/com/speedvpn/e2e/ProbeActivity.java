package com.speedvpn.e2e;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public final class ProbeActivity extends Activity {
    private static final String TAG = "SpeedVPN-Probe";
    private static final String FIXTURE_URL = "http://10.0.2.2:18765/__down?bytes=";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        final String probeId = getIntent().getStringExtra("probe_id") == null
                ? "missing-id" : getIntent().getStringExtra("probe_id");
        final int expectedBytes = Math.max(1, getIntent().getIntExtra("bytes", 4096));

        Thread worker = new Thread(() -> {
            HttpURLConnection connection = null;
            long receivedBytes = 0L;
            int httpStatus = -1;
            boolean passed = false;
            String detail = "unknown";
            try {
                connection = (HttpURLConnection) new URL(FIXTURE_URL + expectedBytes).openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(3000);
                connection.setReadTimeout(5000);
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
            } catch (Exception error) {
                detail = error.getClass().getSimpleName() + ":" + String.valueOf(error.getMessage());
            } finally {
                if (connection != null) connection.disconnect();
                Log.i(TAG, "E2E_PROBE id=" + probeId
                        + " package=" + getPackageName()
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
