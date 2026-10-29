package ru.ytkab0bp.slicebeam.server;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.Response.Status;

/**
 * Local HTTP server that drives the slicer running on the phone.
 *
 * <p>Every route except the static files needs the token, because the page itself is served from
 * here: the settings screen shows the full URL (including {@code ?t=}) and the browser keeps it.
 * Model work is serialised on a single thread so two requests can never slice or load at once.
 */
public class LocalHttpServer extends NanoHTTPD {

    public static final int DEFAULT_PORT = 8080;
    private static final long MAX_UPLOAD = 500L * 1024 * 1024;
    private static final long SLICE_TIMEOUT_MS = 3 * 60 * 1000L;

    private final PlateBridge bridge;
    private final File webDir;
    private final File uploadDir;
    private final String token;
    private final File logFile;
    private final SliceJob job = new SliceJob();
    private final ExecutorService queue = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "orcam-local-jobs");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService watchdog = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "orcam-local-watchdog");
        t.setDaemon(true);
        return t;
    });

    private volatile long lastActivity = System.currentTimeMillis();
    private volatile int authFailures;
    private volatile boolean slicing;

    public LocalHttpServer(int port, PlateBridge bridge, File webDir, File uploadDir, String token)
            throws java.io.IOException {
        this(port, bridge, webDir, uploadDir, token, null);
    }

    public LocalHttpServer(int port, PlateBridge bridge, File webDir, File uploadDir, String token,
                           File logFile) throws java.io.IOException {
        super(port);
        this.bridge = bridge;
        this.webDir = webDir;
        this.uploadDir = uploadDir;
        this.token = token;
        this.logFile = logFile;
        if (!uploadDir.exists()) uploadDir.mkdirs();
        start(SOCKET_READ_TIMEOUT, true);
    }

    public SliceJob getJob() {
        return job;
    }

    public long getIdleMillis() {
        return System.currentTimeMillis() - lastActivity;
    }

    public int getAuthFailures() {
        return authFailures;
    }

    public void shutdown() {
        try {
            stop();
        } catch (Throwable ignored) {
        }
        queue.shutdownNow();
        watchdog.shutdownNow();
    }

    // ------------------------------------------------------------------ routing

    @Override
    public Response serve(IHTTPSession session) {
        lastActivity = System.currentTimeMillis();
        String uri = session.getUri() == null ? "/" : session.getUri();
        Method method = session.getMethod();

        try {
            if (!authorized(session)) {
                authFailures++;
                return text(Status.UNAUTHORIZED, "{\"error\":\"unauthorized\"}");
            }
            authFailures = 0;

            switch (uri) {
                case "/api/status":
                    return text(Status.OK, status());
                case "/api/preview.png":
                    return preview(session);
                case "/api/arrange":
                    run(() -> {
                        bridge.arrange();
                        return null;
                    }, 120);
                    return text(Status.OK, "{\"ok\":true}");
                case "/api/options":
                    return options(session);
                case "/api/slice":
                    return slice();
                case "/api/job":
                    return text(Status.OK, job.toJson());
                case "/api/log":
                    return log();
                case "/api/gcode":
                    return deliver(false);
                case "/api/package":
                    return deliver(true);
                case "/api/send":
                    return send(session);
                default:
                    break;
            }

            if (uri.startsWith("/api/files/")) {
                if (method == Method.PUT || method == Method.POST) return upload(session, uri);
                if (method == Method.DELETE) return remove(uri);
                return text(Status.METHOD_NOT_ALLOWED, "{\"error\":\"method\"}");
            }

            if (uri.startsWith("/api/")) {
                return text(Status.NOT_FOUND, "{\"error\":\"not_found\"}");
            }
            return asset(uri);
        } catch (Throwable t) {
            return text(Status.INTERNAL_ERROR,
                    "{\"error\":\"" + SliceJob.escape(String.valueOf(t)) + "\"}");
        }
    }

    private boolean authorized(IHTTPSession session) {
        String provided = session.getHeaders().get("x-orca-token");
        if (provided == null || provided.isEmpty()) {
            provided = session.getParms().get("t");
        }
        if (provided == null) return false;
        // Constant-time-ish comparison: same length and no early exit.
        if (provided.length() != token.length()) return false;
        int diff = 0;
        for (int i = 0; i < token.length(); i++) {
            diff |= provided.charAt(i) ^ token.charAt(i);
        }
        return diff == 0;
    }

    // ------------------------------------------------------------------ routes

    private String status() throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"ready\":").append(bridge.isReady());
        sb.append(",\"job\":").append(job.toJson());
        sb.append(",\"plate\":").append(bridge.statusJson());
        sb.append('}');
        return sb.toString();
    }

    private Response preview(IHTTPSession session) {
        int width = 640;
        String w = session.getParms().get("w");
        if (w != null) {
            try {
                width = Math.max(160, Math.min(1600, Integer.parseInt(w)));
            } catch (NumberFormatException ignored) {
            }
        }
        try {
            byte[] png = bridge.previewPng(width);
            if (png == null || png.length == 0) {
                return text(Status.NO_CONTENT, "{\"error\":\"no_preview\"}");
            }
            return newFixedLengthResponse(Status.OK, "image/png", new java.io.ByteArrayInputStream(png), png.length);
        } catch (Exception e) {
            return text(Status.INTERNAL_ERROR, "{\"error\":\"" + SliceJob.escape(String.valueOf(e)) + "\"}");
        }
    }

    private Response options(IHTTPSession session) throws Exception {
        Map<String, String> body = readBody(session);
        if (body.isEmpty()) {
            return text(Status.BAD_REQUEST, "{\"error\":\"empty\"}");
        }
        run(() -> {
            bridge.applyOptions(body);
            return null;
        }, 120);
        return text(Status.OK, "{\"ok\":true}");
    }

    private Response slice() {
        if (!bridge.isReady()) {
            return text(Status.CONFLICT, "{\"error\":\"app_closed\"}");
        }
        if (slicing) {
            return text(Status.CONFLICT, "{\"error\":\"busy\",\"job\":" + job.toJson() + "}");
        }
        slicing = true;
        job.start();
        // A print that never comes back must not leave the browser waiting forever.
        watchdog.schedule(() -> {
            if (slicing) {
                slicing = false;
                job.fail("timeout: slicing took longer than 3 minutes");
            }
        }, SLICE_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        queue.submit(() -> {
            try {
                SliceJob.Stats stats = bridge.slice((percent, text) -> job.progress(percent, text));
                job.finish(stats);
            } catch (Throwable t) {
                job.fail(String.valueOf(t));
            } finally {
                slicing = false;
            }
        });
        return text(Status.ACCEPTED, job.toJson());
    }

    /** Last crash trace, so a device without adb can still be diagnosed from the browser. */
    private Response log() {
        if (logFile == null || !logFile.isFile()) {
            return text(Status.OK, "{\"log\":\"\"}");
        }
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(logFile.toPath());
            return newFixedLengthResponse(Status.OK, "text/plain; charset=utf-8",
                    new java.io.ByteArrayInputStream(bytes), bytes.length);
        } catch (Exception e) {
            return text(Status.INTERNAL_ERROR, "{\"error\":\"log_unreadable\"}");
        }
    }

    private Response deliver(boolean asPackage) {
        if (!SliceJob.DONE.equals(job.getState())) {
            return text(Status.CONFLICT, "{\"error\":\"no_slice\"}");
        }
        try {
            final File[] holder = new File[1];
            run(() -> {
                holder[0] = asPackage ? bridge.packageFile() : bridge.gcodeFile();
                return null;
            }, 300);
            File file = holder[0];
            if (file == null || !file.isFile()) {
                return text(Status.NOT_FOUND, "{\"error\":\"missing\"}");
            }
            String name = asPackage && !file.getName().endsWith(".3mf")
                    ? file.getName() + ".3mf" : file.getName();
            Response response = newFixedLengthResponse(Status.OK,
                    "application/octet-stream", new FileInputStream(file), file.length());
            response.addHeader("Content-Disposition", "attachment; filename=\"" + name + "\"");
            response.addHeader("X-Orca-Size", String.valueOf(file.length()));
            return response;
        } catch (Exception e) {
            return text(Status.INTERNAL_ERROR, "{\"error\":\"" + SliceJob.escape(String.valueOf(e)) + "\"}");
        }
    }

    private Response send(IHTTPSession session) {
        boolean print = "1".equals(session.getParms().get("print"));
        boolean use3mf = "3mf".equalsIgnoreCase(session.getParms().get("format"));
        try {
            String message = bridge.sendToPrinter(print, use3mf);
            return text(Status.OK, "{\"ok\":true,\"message\":\"" + SliceJob.escape(message) + "\"}");
        } catch (Exception e) {
            return text(Status.INTERNAL_ERROR, "{\"ok\":false,\"message\":\""
                    + SliceJob.escape(String.valueOf(e)) + "\"}");
        }
    }

    private Response upload(IHTTPSession session, String uri) throws Exception {
        String name = sanitize(uri.substring("/api/files/".length()));
        if (name.isEmpty()) {
            return text(Status.BAD_REQUEST, "{\"error\":\"name\"}");
        }
        long length = -1;
        String header = session.getHeaders().get("content-length");
        if (header != null) {
            try {
                length = Long.parseLong(header);
            } catch (NumberFormatException ignored) {
            }
        }
        if (length <= 0) {
            return text(Status.LENGTH_REQUIRED, "{\"error\":\"content_length\"}");
        }
        if (length > MAX_UPLOAD) {
            return text(Status.PAYLOAD_TOO_LARGE, "{\"error\":\"too_large\"}");
        }

        File target = new File(uploadDir, name);
        // Never close the session stream: it wraps the socket, closing it kills the response too.
        InputStream in = session.getInputStream();
        try (OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            long written = 0;
            int read;
            while (written < length && (read = in.read(buffer, 0, (int) Math.min(buffer.length, length - written))) > 0) {
                out.write(buffer, 0, read);
                written += read;
            }
            if (written != length) {
                target.delete();
                return text(Status.BAD_REQUEST, "{\"error\":\"short_upload\",\"written\":" + written + "}");
            }
        }

        final File file = target;
        int index = run(() -> bridge.addPart(file), 300);
        return text(Status.OK, "{\"index\":" + index + ",\"name\":\"" + SliceJob.escape(name)
                + "\",\"size\":" + length + "}");
    }

    private Response remove(String uri) throws Exception {
        int index;
        try {
            index = Integer.parseInt(uri.substring("/api/files/".length()));
        } catch (NumberFormatException e) {
            return text(Status.BAD_REQUEST, "{\"error\":\"index\"}");
        }
        run(() -> {
            bridge.removePart(index);
            return null;
        }, 120);
        return text(Status.OK, "{\"ok\":true}");
    }

    // ------------------------------------------------------------------ helpers

    private <T> T run(Callable<T> task, int seconds) throws Exception {
        Future<T> future = queue.submit(task);
        return future.get(seconds, TimeUnit.SECONDS);
    }

    private Map<String, String> readBody(IHTTPSession session) throws Exception {
        Map<String, String> result = new HashMap<>();
        String lengthHeader = session.getHeaders().get("content-length");
        int length = lengthHeader == null ? 0 : (int) Math.min(64 * 1024, Long.parseLong(lengthHeader.trim()));
        if (length <= 0) return result;
        byte[] buffer = new byte[length];
        int read = 0;
        InputStream in = session.getInputStream();
        while (read < length) {
            int n = in.read(buffer, read, length - read);
            if (n <= 0) break;
            read += n;
        }
        String json = new String(buffer, 0, read, "UTF-8").trim();
        // Flat object only: {"key":"value",...}
        int i = 0;
        while ((i = json.indexOf('"', i)) != -1) {
            int keyEnd = json.indexOf('"', i + 1);
            if (keyEnd == -1) break;
            String key = json.substring(i + 1, keyEnd);
            int colon = json.indexOf(':', keyEnd);
            if (colon == -1) break;
            int valueStart = json.indexOf('"', colon);
            int valueEnd = valueStart == -1 ? -1 : json.indexOf('"', valueStart + 1);
            if (valueStart == -1 || valueEnd == -1) break;
            result.put(key, json.substring(valueStart + 1, valueEnd));
            i = valueEnd + 1;
        }
        return result;
    }

    private Response asset(String uri) {
        String path = uri.equals("/") || uri.isEmpty() ? "/index.html" : uri;
        File file = new File(webDir, path.substring(1));
        if (!file.isFile() || !file.getAbsolutePath().startsWith(webDir.getAbsolutePath())) {
            return text(Status.NOT_FOUND, "not found");
        }
        try {
            Response response = newFixedLengthResponse(Status.OK, mime(path),
                    new FileInputStream(file), file.length());
            response.addHeader("Cache-Control", "no-cache");
            return response;
        } catch (Exception e) {
            return text(Status.INTERNAL_ERROR, "unreadable");
        }
    }

    private static String mime(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html")) return "text/html; charset=utf-8";
        if (lower.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    private static Response text(Status status, String body) {
        return newFixedLengthResponse(status, "application/json; charset=utf-8", body);
    }

    /** Rejects anything but a plain file name: no separators, no traversal, no empty names. */
    static String sanitize(String raw) {
        if (raw == null) return "";
        String name = raw;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (name.equals(".") || name.equals("..") || name.isEmpty()) return "";
        if (name.length() > 96) {
            int dot = name.lastIndexOf('.');
            String extension = dot > 0 ? name.substring(dot) : "";
            name = name.substring(0, 96 - extension.length()) + extension;
        }
        return name;
    }
}
