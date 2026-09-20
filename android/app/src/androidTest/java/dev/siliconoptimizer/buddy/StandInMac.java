package dev.siliconoptimizer.buddy;

import android.os.Bundle;

import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The stand-in Mac on the development machine, reached from the emulator at 10.0.2.2.
 *
 * It is `demo_mac.py`, extended for M5 with the Mac's `/ondevice/models` routes and serving
 * the tiny test models — stories260K and SmolLM2 135M — pinned by repository, commit and
 * SHA-256. Not `adb reverse`: the emulator's own address for its host, the way a phone
 * reaches the Mac over the tailnet. Start it with `standin.sh start` (see docs/PLAN.md);
 * `-Pandroid.testInstrumentationRunnerArguments.standin=host:port` points elsewhere.
 *
 * Its control token is the stand-in's own made-up one, which is how a test mints a
 * pairing code and flips its switches — loopback on the host, as on the real Mac.
 */
final class StandInMac {

    static final String CONTROL = "demo-token";

    final String host;
    final int port;

    StandInMac() {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String address = arguments.getString("standin", "10.0.2.2:8916");
        host = address.substring(0, address.lastIndexOf(':'));
        port = Integer.parseInt(address.substring(address.lastIndexOf(':') + 1));
    }

    /** Throws with instructions when the stand-in is not running. */
    void check() {
        try {
            request("GET", "/health", null);
        } catch (Exception unreachable) {
            throw new AssertionError("The stand-in Mac is not answering at " + host + ":" + port
                + ". Start it on the host: /Volumes/T9/Silicon/worktrees/m5-standin/standin.sh start", unreachable);
        }
    }

    /** A one-time pairing code, minted the way a CLI or a test does on the real Mac. */
    String invitation() throws Exception {
        return new JSONObject(request("POST", "/buddy/invitations", "{\"scope\":\"full\"}")).getString("code");
    }

    /** Whether the stand-in lists [id] on `/ondevice/models` (Qwen3.5 2B only with QWEN=1). */
    boolean offers(String id) throws Exception {
        JSONArray models = new JSONObject(request("GET", "/ondevice/models", null)).getJSONArray("models");
        for (int i = 0; i < models.length(); i++) if (models.getJSONObject(i).getString("id").equals(id)) return true;
        return false;
    }

    /** Every request line the stand-in has seen, oldest first. */
    List<String> requests() throws Exception {
        JSONArray lines = new JSONObject(request("GET", "/demo/requests", null)).getJSONArray("requests");
        List<String> out = new ArrayList<>();
        for (int i = 0; i < lines.length(); i++) out.add(lines.getString(i));
        return out;
    }

    /**
     * What the phone has asked of the `/ondevice` routes, oldest first — and only since the
     * last [ondevice] call that cleared them, which is what makes this readable at all: the
     * full request log is a ring buffer that a test's own polling rolls over in seconds.
     */
    List<String> ondeviceRequests() throws Exception {
        JSONArray lines = new JSONObject(request("GET", "/demo/ondevice/requests", null)).getJSONArray("requests");
        List<String> out = new ArrayList<>();
        for (int i = 0; i < lines.length(); i++) out.add(lines.getString(i));
        return out;
    }

    /** Sets the stand-in's phone-model switches and states. */
    void ondevice(String json) throws Exception {
        request("POST", "/demo/ondevice", json);
    }

    /** The Mac goes away: every connection is dropped unanswered for [seconds]. 0 brings it back. */
    void unreachable(int seconds) throws Exception {
        request("POST", "/demo/unreachable", "{\"seconds\":" + seconds + "}");
    }

    private String request(String method, String path, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://" + host + ":" + port + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("Authorization", "Bearer " + CONTROL);
        if (body != null) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
        }
        int status = connection.getResponseCode();
        InputStream stream = status < 400 ? connection.getInputStream() : connection.getErrorStream();
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        if (stream != null) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) >= 0) text.write(buffer, 0, read);
            stream.close();
        }
        connection.disconnect();
        if (status >= 400) throw new AssertionError(method + " " + path + " answered " + status + ": " + text);
        return text.toString("UTF-8");
    }
}
