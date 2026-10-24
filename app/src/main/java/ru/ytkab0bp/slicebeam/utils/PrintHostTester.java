package ru.ytkab0bp.slicebeam.utils;

import android.text.TextUtils;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import ru.ytkab0bp.slicebeam.SliceBeam;

/**
 * Connectivity check for a configured print host, mirroring the "Test" button of desktop OrcaSlicer.
 * Runs on a background thread and reports through {@link Callback} on the caller's thread contract
 * (callers are expected to hop back to the UI thread themselves).
 */
public final class PrintHostTester {

    public interface Callback {
        void onResult(boolean ok, String message);
    }

    private PrintHostTester() {}

    public static void test(final String hostType,
                            final String printHost,
                            final String apiKey,
                            final String flashforgeSerialNumber,
                            final Callback callback) {
        new Thread(() -> {
            boolean ok;
            String message;
            try {
                Result r = run(hostType, printHost, apiKey, flashforgeSerialNumber);
                ok = r.ok;
                message = r.message;
            } catch (Exception e) {
                ok = false;
                message = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            callback.onResult(ok, message);
        }, "print-host-test").start();
    }

    private static final class Result {
        final boolean ok;
        final String message;

        Result(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }
    }

    private static Result run(String hostType, String printHost, String apiKey, String flashforgeSerialNumber) throws Exception {
        String type = hostType == null ? "octoprint" : hostType.toLowerCase(Locale.ROOT).trim();
        String host = normalizeHost(printHost);
        if (TextUtils.isEmpty(host)) {
            return new Result(false, SliceBeam.INSTANCE.getString(
                    ru.ytkab0bp.slicebeam.R.string.PrinterTestNoHost));
        }

        switch (type) {
            case "flashforge":
                // Desktop stores the check code in printhost_apikey and the serial in
                // flashforge_serial_number; /detail wants both.
                return testFlashforge(host, flashforgeSerialNumber, apiKey);
            case "moonraker":
                return testMoonraker(host, apiKey);
            default:
                return testOctoPrint(host, apiKey);
        }
    }

    /** Accepts "192.168.1.42", "http://ip:port/path" and returns "scheme://host[:port]". */
    private static String normalizeHost(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            s = "http://" + s;
        }
        // Drop any trailing path, keeping scheme://host:port
        int schemeEnd = s.indexOf("://") + 3;
        int slash = s.indexOf('/', schemeEnd);
        if (slash > 0) s = s.substring(0, slash);
        return s;
    }

    private static String hostAndPort(String baseUrl) {
        return baseUrl.substring(baseUrl.indexOf("://") + 3);
    }

    private static Result testFlashforge(String baseUrl, String serial, String checkCode) throws Exception {
        if (TextUtils.isEmpty(serial) || TextUtils.isEmpty(checkCode)) {
            return new Result(false, SliceBeam.INSTANCE.getString(
                    ru.ytkab0bp.slicebeam.R.string.PrinterTestFlashforgeMissingCreds));
        }
        String host = hostAndPort(baseUrl);
        if (!host.contains(":")) {
            host = host + ":8898";
        }
        String url = "http://" + host + "/detail";
        String body = "{\"serialNumber\":" + JSONObject.quote(serial)
                + ",\"checkCode\":" + JSONObject.quote(checkCode) + "}";

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(8000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        try {
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int status = conn.getResponseCode();
            String response = read(conn, status < 400);
            if (status >= 400) {
                return new Result(false, "HTTP " + status + ": " + response);
            }
            JSONObject obj = new JSONObject(response);
            int code = obj.optInt("code", obj.optInt("err", 0));
            if (code != 0) {
                String msg = obj.optString("message", obj.optString("msg", ""));
                return new Result(false, SliceBeam.INSTANCE.getString(
                        ru.ytkab0bp.slicebeam.R.string.PrinterTestFlashforgeError, code, msg));
            }
            String detail = obj.has("detail") ? String.valueOf(obj.get("detail")) : response;
            return new Result(true, SliceBeam.INSTANCE.getString(
                    ru.ytkab0bp.slicebeam.R.string.PrinterTestOK, shorten(detail)));
        } finally {
            conn.disconnect();
        }
    }

    private static Result testMoonraker(String baseUrl, String apiKey) throws Exception {
        String host = hostAndPort(baseUrl);
        if (!host.contains(":")) {
            host = host + ":7125";
        }
        HttpURLConnection conn = (HttpURLConnection) new URL("http://" + host + "/server/info").openConnection();
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(8000);
        if (!TextUtils.isEmpty(apiKey)) {
            conn.setRequestProperty("X-Api-Key", apiKey);
        }
        try {
            int status = conn.getResponseCode();
            if (status != 200) {
                return new Result(false, "HTTP " + status + ": " + read(conn, false));
            }
            String body = read(conn, true);
            JSONObject obj = new JSONObject(body);
            String version = obj.optJSONObject("result") != null
                    ? obj.optJSONObject("result").optString("moonraker_version", "")
                    : "";
            return new Result(true, SliceBeam.INSTANCE.getString(
                    ru.ytkab0bp.slicebeam.R.string.PrinterTestMoonrakerOK, version));
        } finally {
            conn.disconnect();
        }
    }

    private static Result testOctoPrint(String baseUrl, String apiKey) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + "/api/version").openConnection();
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(8000);
        if (!TextUtils.isEmpty(apiKey)) {
            conn.setRequestProperty("X-Api-Key", apiKey);
        }
        try {
            int status = conn.getResponseCode();
            if (status == 401 || status == 403) {
                return new Result(false, SliceBeam.INSTANCE.getString(
                        ru.ytkab0bp.slicebeam.R.string.PrinterTestUnauthorized));
            }
            if (status != 200) {
                return new Result(false, "HTTP " + status + ": " + read(conn, false));
            }
            String body = read(conn, true);
            JSONObject obj = new JSONObject(body);
            String version = obj.optString("server", obj.optString("api", ""));
            return new Result(true, SliceBeam.INSTANCE.getString(
                    ru.ytkab0bp.slicebeam.R.string.PrinterTestOK, shorten(version)));
        } finally {
            conn.disconnect();
        }
    }

    /** Fallback for hosts that only speak raw TCP. */
    public static boolean canConnect(String hostAndPort, int defaultPort) {
        try {
            String host = hostAndPort;
            int port = defaultPort;
            int idx = host.indexOf(':');
            if (idx > 0) {
                port = Integer.parseInt(host.substring(idx + 1));
                host = host.substring(0, idx);
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 4000);
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    private static String read(HttpURLConnection conn, boolean input) throws Exception {
        InputStream in = input ? conn.getInputStream() : conn.getErrorStream();
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private static String shorten(String s) {
        if (s == null) return "";
        return s.length() > 220 ? s.substring(0, 220) + "…" : s;
    }
}