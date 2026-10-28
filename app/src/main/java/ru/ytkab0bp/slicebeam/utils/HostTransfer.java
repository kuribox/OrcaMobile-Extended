package ru.ytkab0bp.slicebeam.utils;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import ru.ytkab0bp.slicebeam.BuildConfig;
import ru.ytkab0bp.slicebeam.SliceBeam;
import ru.ytkab0bp.slicebeam.config.ConfigObject;
import ru.ytkab0bp.slicebeam.fragment.BedFragment;
import ru.ytkab0bp.slicebeam.slic3r.GCodeProcessorResult;

/**
 * Everything after slicing that moves the file out of the app: building the package, sharing it and
 * uploading it to the configured printer. Shared by the slice menu and the guided assistant so both
 * speak exactly the same protocol.
 */
public final class HostTransfer {

    /** Base64 of "[]", the empty material mapping list the Flashforge API expects. */
    private static final String EMPTY_MATERIAL_MAPPINGS = "W1td";

    public interface Callback {
        void onResult(boolean ok, String message);
    }

    private HostTransfer() {}

    // ------------------------------------------------------------------ package

    /** Returns the file to hand over: the sliced G-code, or a .gcode.3mf package built next to it. */
    public static File preparePayload(BedFragment bed, String recommendedName, boolean use3mf) throws Exception {
        File gcode = BedFragment.getTempGCodePath();
        if (gcode == null || !gcode.isFile()) {
            throw new IllegalStateException("no sliced gcode");
        }
        if (!use3mf) return gcode;

        File dest = new File(SliceBeam.INSTANCE.getCacheDir(),
                "export_" + System.currentTimeMillis() + ".gcode.3mf");
        ConfigObject printer = SliceBeam.CONFIG.findPrinter(SliceBeam.CONFIG.presets.get("printer"));
        GCodeProcessorResult result = bed != null && bed.getGlView() != null
                ? bed.getGlView().getRenderer().getGcodeResult() : null;
        GCode3mfPackager.create(gcode, dest, printer, result);
        return dest;
    }

    /** Display name with the right extension for the chosen format. */
    public static String payloadName(String recommendedName, boolean use3mf) {
        String base = recommendedName == null ? "" : recommendedName.trim();
        if (base.isEmpty()) base = "print.gcode";
        String lower = base.toLowerCase(Locale.ROOT);
        if (use3mf) {
            if (lower.endsWith(".gcode.3mf")) return base;
            if (lower.endsWith(".gcode")) return base + ".3mf";
            if (lower.endsWith(".3mf")) return base;
            return base + ".gcode.3mf";
        }
        if (lower.endsWith(".gcode.3mf")) return base.substring(0, base.length() - 4);
        return lower.endsWith(".gcode") ? base : base + ".gcode";
    }

    public static Intent shareIntent(Context ctx, File file, String displayName, boolean use3mf) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType(use3mf ? "application/vnd.ms-package.3dmanufacturing-3dmodel+xml" : "application/x-gcode");
        Uri uri = FileProvider.getUriForFile(ctx, BuildConfig.APPLICATION_ID + ".provider", file, displayName);
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return Intent.createChooser(intent, displayName);
    }

    // ------------------------------------------------------------------ send

    /**
     * Uploads the payload to the printer configured in the active profile.
     * Blocking: call it from a worker thread.
     */
    public static void send(BedFragment bed, boolean print, boolean use3mf, Callback callback) {
        ConfigObject printer = SliceBeam.CONFIG.findPrinter(SliceBeam.CONFIG.presets.get("printer"));
        if (printer == null) {
            callback.onResult(false, "No hay perfil de impresora activo.");
            return;
        }

        String type = printer.get("host_type");
        if (TextUtils.isEmpty(type)) type = "octoprint";
        String host = printer.get("print_host");
        if (TextUtils.isEmpty(host)) {
            callback.onResult(false, SliceBeam.INSTANCE.getString(
                    ru.ytkab0bp.slicebeam.R.string.PrinterTestMissingHost));
            return;
        }

        String apiKey = printer.get("printhost_apikey");
        // Profiles saved by earlier builds kept these under the legacy keys.
        String serialNumber = firstNonEmpty(printer.get("flashforge_serial_number"), printer.get("printhost_user"));
        String checkCode = firstNonEmpty(apiKey, printer.get("printhost_password"));

        boolean flashforge = "flashforge".equalsIgnoreCase(type);
        boolean hasCredentials = !TextUtils.isEmpty(serialNumber) && !TextUtils.isEmpty(checkCode);
        // The console protocol only carries plain G-code, so the package is pointless there.
        boolean sendPackage = use3mf && (!flashforge || hasCredentials);

        File payload;
        try {
            payload = preparePayload(bed, recommendedName(bed), sendPackage);
        } catch (Exception e) {
            callback.onResult(false, "No se pudo preparar el archivo: " + e.getMessage());
            return;
        }
        String name = payloadName(recommendedName(bed), sendPackage);

        try {
            if (flashforge) {
                if (hasCredentials) {
                    uploadFlashforgeHttp(host, serialNumber, checkCode, payload, name, print, callback);
                } else {
                    FlashforgeSerial.Result r = FlashforgeSerial.upload(
                            bareHost(host), payload, name, print, null);
                    callback.onResult(r.ok, r.message);
                }
                return;
            }
            uploadOctoPrint(host, apiKey, payload, name, print, callback);
        } catch (Exception e) {
            callback.onResult(false, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static String recommendedName(BedFragment bed) {
        try {
            if (bed != null && bed.getGlView() != null && bed.getGlView().getRenderer().getGcodeResult() != null) {
                return bed.getGlView().getRenderer().getGcodeResult().getRecommendedName();
            }
        } catch (Exception ignored) {
        }
        return "print";
    }

    private static String firstNonEmpty(String first, String second) {
        return TextUtils.isEmpty(first) ? (second == null ? "" : second) : first;
    }

    private static String bareHost(String raw) {
        String host = raw.replaceFirst("(?i)^https?://", "");
        int slash = host.indexOf('/');
        if (slash > 0) host = host.substring(0, slash);
        return host;
    }

    /**
     * Flashforge local API: multipart POST /uploadGcode on port 8898 with the serial and check code in
     * headers. Returns a message describing the outcome.
     */
    private static boolean uploadFlashforgeHttp(String host, String serial, String checkCode,
                                                File file, String name, boolean print, Callback callback) throws Exception {
        String base = host.startsWith("http://") || host.startsWith("https://") ? host : "http://" + host;
        if (!base.contains(":8898")) {
            base = bareHost(base);
            base = "http://" + (base.contains(":") ? base : base + ":8898");
        }
        String boundary = "----OrcaM" + System.currentTimeMillis();
        HttpURLConnection conn = (HttpURLConnection) new URL(base + "/uploadGcode").openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(120000);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setRequestProperty("serialNumber", serial);
        conn.setRequestProperty("checkCode", checkCode);
        conn.setRequestProperty("fileSize", String.valueOf(file.length()));
        conn.setRequestProperty("printNow", print ? "true" : "false");
        conn.setRequestProperty("levelingBeforePrint", "false");
        conn.setRequestProperty("flowCalibration", "false");
        conn.setRequestProperty("firstLayerInspection", "false");
        conn.setRequestProperty("timeLapseVideo", "false");
        conn.setRequestProperty("useMatlStation", "false");
        conn.setRequestProperty("gcodeToolCnt", "1");
        conn.setRequestProperty("materialMappings", EMPTY_MATERIAL_MAPPINGS);

        try (OutputStream out = conn.getOutputStream()) {
            out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Disposition: form-data; name=\"gcodeFile\"; filename=\"" + name + "\"\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.write("Content-Type: application/octet-stream\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            try (FileInputStream in = new FileInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int len;
                while ((len = in.read(buffer)) > 0) {
                    out.write(buffer, 0, len);
                }
            }
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }

        int status = conn.getResponseCode();
        String body = readBody(conn, status);
        boolean ok = status >= 200 && status < 300;
        if (ok) {
            // validate_local_api_response(): a JSON object whose code/err is 0.
            try {
                JSONObject json = new JSONObject(body);
                int code = json.optInt("code", json.optInt("err", 0));
                if (code != 0) {
                    String message = json.optString("message", json.optString("msg", ""));
                    callback.onResult(false, "La impresora rechazó el envío (" + code + ")" +
                            (message.isEmpty() ? "" : ": " + message));
                    return false;
                }
            } catch (Exception ignored) {
                // Non-JSON answer: accept the HTTP status.
            }
            callback.onResult(true, print ? "Enviado. Impresión iniciada." : "Archivo enviado a la impresora.");
            return true;
        }
        callback.onResult(false, "HTTP " + status + (body.isEmpty() ? "" : ": " + trim(body)));
        return false;
    }

    /** OctoPrint / Klipper-compatible API. */
    private static boolean uploadOctoPrint(String host, String apiKey, File file, String name, boolean print,
                                           Callback callback) throws Exception {
        String base = host.startsWith("http://") || host.startsWith("https://") ? host : "http://" + host;
        int slash = base.indexOf('/', base.indexOf("://") + 3);
        if (slash > 0) base = base.substring(0, slash);

        String boundary = "----OrcaM" + System.currentTimeMillis();
        HttpURLConnection conn = (HttpURLConnection) new URL(base + "/api/files/local").openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(120000);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        if (!TextUtils.isEmpty(apiKey)) {
            conn.setRequestProperty("X-Api-Key", apiKey);
        }

        try (OutputStream out = conn.getOutputStream()) {
            writeField(out, boundary, "select", String.valueOf(print));
            writeField(out, boundary, "print", String.valueOf(print));
            out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + name + "\"\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.write("Content-Type: text/plain\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            try (FileInputStream in = new FileInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int len;
                while ((len = in.read(buffer)) > 0) {
                    out.write(buffer, 0, len);
                }
            }
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }

        int status = conn.getResponseCode();
        String body = readBody(conn, status);
        boolean ok = status >= 200 && status < 300;
        if (ok) {
            callback.onResult(true, print ? "Enviado. Impresión iniciada." : "Archivo enviado a la impresora.");
        } else {
            callback.onResult(false, "HTTP " + status + (body.isEmpty() ? "" : ": " + trim(body)));
        }
        return ok;
    }

    private static void writeField(OutputStream out, String boundary, String name, String value) throws Exception {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write((value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private static String readBody(HttpURLConnection conn, int status) {
        try {
            java.io.InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) return "";
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) > 0) out.write(buffer, 0, len);
            in.close();
            return out.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static String trim(String text) {
        String t = text.replace('\n', ' ').trim();
        return t.length() > 300 ? t.substring(0, 300) + "…" : t;
    }
}
