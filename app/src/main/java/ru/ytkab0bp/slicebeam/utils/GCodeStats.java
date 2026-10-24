package ru.ytkab0bp.slicebeam.utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Single-pass statistics over a sliced G-code file, used by the print-info panel to show the numbers
 * behind each preview color scheme (desktop OrcaSlicer shows the same legend values).
 *
 * <p>Everything here is derived from the G-code itself: F values give speed, E volume relative to the
 * segment length gives effective line width and volumetric flow, Z steps give layer heights.
 */
public final class GCodeStats {

    /** Min/max/mean of one metric, in the metric's own unit. */
    public static final class Metric {
        public float min = Float.NaN;
        public float max = Float.NaN;
        public float avg = Float.NaN;
        public int samples;

        void add(float value, float weight) {
            if (Float.isNaN(value) || Float.isInfinite(value)) return;
            if (Float.isNaN(min) || value < min) min = value;
            if (Float.isNaN(max) || value > max) max = value;
            totalWeighted += value * weight;
            totalWeight += weight;
            samples++;
        }

        private double totalWeighted;
        private double totalWeight;

        void finish() {
            avg = totalWeight > 0 ? (float) (totalWeighted / totalWeight) : min;
        }

        public boolean hasData() {
            return samples > 0;
        }
    }

    public final Metric speed = new Metric();        // mm/s
    public final Metric flow = new Metric();         // mm³/s
    public final Metric width = new Metric();        // mm
    public final Metric height = new Metric();       // mm
    public final Metric temperature = new Metric();  // °C
    public final Metric fan = new Metric();          // %
    public final Metric layerTime = new Metric();    // s

    public int layers;
    public int tools = 1;
    public double filamentMm;

    private GCodeStats() {}

    public static GCodeStats analyze(File gcode) throws IOException {
        GCodeStats stats = new GCodeStats();
        float filamentDiameter = 1.75f;
        float nominalHeight = 0.2f;

        float x = 0, y = 0, z = 0, f = 0, e = 0;
        boolean relativeE = false;
        float layerZ = 0;
        double layerSeconds = 0;
        int moveCount = 0;
        int layerChanges = 0;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(gcode), StandardCharsets.ISO_8859_1), 1 << 16)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;
                char c = line.charAt(0);

                if (c == ';') {
                    String trimmed = line.substring(1).trim();
                    float v = parseSetting(trimmed);
                    if (trimmed.startsWith("filament_diameter")) {
                        if (v > 0) filamentDiameter = v;
                    } else if (trimmed.startsWith("layer_height")) {
                        if (v > 0) nominalHeight = v;
                    } else if (trimmed.startsWith("filament used [mm]")) {
                        if (v > 0) stats.filamentMm = v;
                    } else if (trimmed.startsWith("total layer number")) {
                        if (v > 0) stats.layers = (int) v;
                    }
                    continue;
                }

                if (c == 'M') {
                    if (line.startsWith("M82")) {
                        relativeE = false;
                    } else if (line.startsWith("M83")) {
                        relativeE = true;
                    } else if (line.startsWith("M104") || line.startsWith("M109")) {
                        // S0 is a cooldown command at the end of the file, not a printing temperature.
                        float s = parseValue(line, 'S');
                        if (!Float.isNaN(s) && s > 0) stats.temperature.add(s, 1);
                    } else if (line.startsWith("M106")) {
                        float s = parseValue(line, 'S');
                        float p = parseValue(line, 'P');
                        float duty = !Float.isNaN(s) ? s : p;
                        if (!Float.isNaN(duty)) stats.fan.add(Math.min(100f, Math.max(0f, duty) / 255f * 100f), 1);
                    } else if (line.startsWith("M140") || line.startsWith("M190")) {
                        // bed temperature: not part of the preview schemes
                    }
                    continue;
                }

                if (line.startsWith("G0") || line.startsWith("G1") || line.startsWith("G2") || line.startsWith("G3")) {
                    float nx = parseValue(line, 'X');
                    float ny = parseValue(line, 'Y');
                    float nz = parseValue(line, 'Z');
                    float nf = parseValue(line, 'F');
                    float ne = parseValue(line, 'E');

                    if (!Float.isNaN(nf) && nf > 0) f = nf;
                    double dx = !Float.isNaN(nx) ? nx - x : 0;
                    double dy = !Float.isNaN(ny) ? ny - y : 0;
                    double length = Math.sqrt(dx * dx + dy * dy);

                    if (!Float.isNaN(nz) && Math.abs(nz - z) > 0.0001f) {
                        if (nz > z && z > 0) {
                            // New layer: close the previous one (only when it actually printed something).
                            if (layerSeconds > 0) {
                                stats.layerTime.add((float) layerSeconds, 1);
                                layerSeconds = 0;
                            }
                            layerChanges++;
                            layerZ = nz - z;
                            if (layerZ > 0) stats.height.add(layerZ, 1);
                        }
                        z = nz;
                    }
                    if (!Float.isNaN(nx)) x = nx;
                    if (!Float.isNaN(ny)) y = ny;

                    if (!Float.isNaN(ne)) {
                        float dE = relativeE ? ne : ne - e;
                        e = ne;
                        if (dE > 0 && length > 0.0001 && f > 0) {
                            double speed = f / 60.0;              // mm/s
                            double seconds = length / speed;       // s for this segment
                            if (seconds > 0 && seconds < 3600) {
                                double filamentArea = Math.PI * (filamentDiameter / 2.0) * (filamentDiameter / 2.0);
                                double volume = dE * filamentArea;  // mm³
                                double flowRate = volume / seconds; // mm³/s
                                float h = layerZ > 0 ? layerZ : nominalHeight;
                                double w = volume / (length * h);  // effective line width

                                stats.speed.add((float) speed, (float) seconds);
                                stats.flow.add((float) flowRate, (float) seconds);
                                if (w > 0 && w < 10) stats.width.add((float) w, (float) seconds);
                                layerSeconds += seconds;
                                moveCount++;
                            }
                        }
                    }
                    continue;
                }

                if (line.startsWith("T") && line.length() > 1 && Character.isDigit(line.charAt(1))) {
                    int tool = Character.getNumericValue(line.charAt(1));
                    if (tool + 1 > stats.tools) stats.tools = tool + 1;
                }
            }
        }

        if (layerSeconds > 0) stats.layerTime.add((float) layerSeconds, 1);
        if (layerChanges > 0) stats.layers = layerChanges + 1;
        else if (stats.layers <= 0) stats.layers = stats.height.samples;
        stats.speed.finish();
        stats.flow.finish();
        stats.width.finish();
        stats.height.finish();
        stats.temperature.finish();
        stats.fan.finish();
        stats.layerTime.finish();
        if (moveCount == 0 && stats.filamentMm <= 0) {
            // Nothing printable detected; callers fall back to hiding the block.
            return stats;
        }
        return stats;
    }

    /** Parses `key = value` from an already de-commented setting line. */
    private static float parseSetting(String trimmed) {
        int eq = trimmed.indexOf('=');
        if (eq < 0) return Float.NaN;
        return parseFloat(trimmed.substring(eq + 1));
    }

    /** Parses the first occurrence of `letter<number>` (e.g. F1800), or NaN when absent. */
    private static float parseValue(String line, char letter) {
        for (int i = 1; i < line.length() - 1; i++) {
            if (line.charAt(i) != letter) continue;
            char prev = line.charAt(i - 1);
            if (prev != ' ' && prev != '\t' && !Character.isLetter(prev)) continue;
            if (!Character.isDigit(line.charAt(i + 1)) && line.charAt(i + 1) != '-' && line.charAt(i + 1) != '.') continue;
            int start = i + 1;
            int end = start;
            while (end < line.length()) {
                char ch = line.charAt(end);
                if (Character.isDigit(ch) || ch == '.' || ch == '-') end++;
                else break;
            }
            return parseFloat(line.substring(start, end));
        }
        return Float.NaN;
    }

    private static float parseFloat(String raw) {
        try {
            return Float.parseFloat(raw.trim());
        } catch (Exception e) {
            return Float.NaN;
        }
    }

    public static String format(float value, String unit) {
        if (Float.isNaN(value)) return "—";
        String num;
        if (Math.abs(value) >= 100) num = String.format(Locale.getDefault(), "%.0f", value);
        else if (Math.abs(value) >= 10) num = String.format(Locale.getDefault(), "%.1f", value);
        else num = String.format(Locale.getDefault(), "%.2f", value);
        return unit.isEmpty() ? num : num + " " + unit;
    }
}