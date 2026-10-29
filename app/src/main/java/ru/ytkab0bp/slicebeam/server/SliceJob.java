package ru.ytkab0bp.slicebeam.server;

import java.util.Locale;

/** State of the single slice job the server allows at a time. */
public class SliceJob {

    public static final String IDLE = "idle";
    public static final String SLICING = "slicing";
    public static final String DONE = "done";
    public static final String ERROR = "error";

    public static final class Stats {
        public int seconds;
        public double grams;
        public int layers;
        public double speed;
        public String sha256 = "";
        public long size;

        public Stats copy() {
            Stats s = new Stats();
            s.seconds = seconds;
            s.grams = grams;
            s.layers = layers;
            s.speed = speed;
            s.sha256 = sha256;
            s.size = size;
            return s;
        }
    }

    private volatile String state = IDLE;
    private volatile int progress;
    private volatile String message = "";
    private volatile String error;
    private volatile Stats stats = new Stats();

    public boolean isBusy() {
        return SLICING.equals(state);
    }

    public String getState() {
        return state;
    }

    public Stats getStats() {
        return stats;
    }

    public synchronized void start() {
        state = SLICING;
        progress = 0;
        message = "";
        error = null;
        stats = new Stats();
    }

    public void progress(int percent, String text) {
        progress = Math.max(0, Math.min(100, percent));
        if (text != null) message = text;
    }

    public synchronized void finish(Stats result) {
        if (result != null) stats = result;
        progress = 100;
        state = DONE;
        message = "done";
    }

    public synchronized void fail(String reason) {
        state = ERROR;
        error = reason;
        message = reason;
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"state\":\"").append(state).append('"');
        sb.append(",\"progress\":").append(progress);
        sb.append(",\"message\":\"").append(escape(message)).append('"');
        sb.append(",\"error\":").append(error == null ? "null" : "\"" + escape(error) + "\"");
        Stats s = stats;
        sb.append(",\"stats\":{");
        sb.append("\"seconds\":").append(s.seconds);
        sb.append(",\"grams\":").append(String.format(Locale.ROOT, "%.2f", s.grams));
        sb.append(",\"layers\":").append(s.layers);
        sb.append(",\"speed\":").append(String.format(Locale.ROOT, "%.1f", s.speed));
        sb.append(",\"sha256\":\"").append(escape(s.sha256)).append('"');
        sb.append(",\"size\":").append(s.size);
        sb.append("}}");
        return sb.toString();
    }

    public static String escape(String value) {
        if (value == null) return "";
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }
}
