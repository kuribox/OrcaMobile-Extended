package ru.ytkab0bp.slicebeam.utils;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.Base64;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plate thumbnails for the .gcode.3mf package.
 *
 * <p>The native exporter writes the G-code with a null thumbnail generator
 * ({@code print.export_gcode(path, &result, nullptr)} in beam_native.cpp), so sliced files carry no
 * {@code ; thumbnail} blocks. When none is present the preview is rasterised here from the toolpaths
 * instead of shipping a blank image, which is why printers showed an empty plate.
 */
public final class GCodeThumbnail {

    private static final Pattern THUMBNAIL_BEGIN =
            Pattern.compile("^;\\s*thumbnail\\s+begin\\s+(\\d+)x(\\d+)\\s+(\\d+).*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern THUMBNAIL_END =
            Pattern.compile("^;\\s*thumbnail\\s+end.*$", Pattern.CASE_INSENSITIVE);

    /** Background and toolpath colours of the generated preview (Bambu/Orca plate style). */
    private static final int BACKGROUND = 0xFF12161C;
    private static final int PLATE = 0xFF1F2733;
    private static final int PATH_COLOR = 0xFFE0A030;

    private GCodeThumbnail() {}

    /** Returns the largest embedded thumbnail, or null when the G-code has none. */
    public static byte[] extract(File gcode) {
        byte[] best = null;
        int bestArea = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(gcode), StandardCharsets.ISO_8859_1), 1 << 16)) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher begin = THUMBNAIL_BEGIN.matcher(line);
                if (!begin.matches()) {
                    if (line.startsWith("G1 ") || line.startsWith("G0 ")) {
                        // Thumbnails live in the header; stop looking once the moves start.
                        break;
                    }
                    continue;
                }
                int width = Integer.parseInt(begin.group(1));
                int height = Integer.parseInt(begin.group(2));
                StringBuilder base64 = new StringBuilder();
                while ((line = reader.readLine()) != null) {
                    if (THUMBNAIL_END.matcher(line).matches()) break;
                    if (line.startsWith(";")) base64.append(line.substring(1).trim());
                }
                try {
                    byte[] png = Base64.decode(base64.toString(), Base64.DEFAULT);
                    if (png.length > 8 && (png[0] & 0xFF) == 0x89 && png[1] == 'P') {
                        int area = width * height;
                        if (area > bestArea) {
                            bestArea = area;
                            best = png;
                        }
                    }
                } catch (IllegalArgumentException ignored) {
                    // Malformed block: keep scanning.
                }
            }
        } catch (IOException ignored) {
        }
        return best;
    }

    /** Decodes and scales a PNG to a square of the given side, keeping the aspect ratio. */
    public static byte[] scaleTo(byte[] png, int size) {
        if (png == null) return null;
        Bitmap source = BitmapFactory.decodeByteArray(png, 0, png.length);
        if (source == null) return null;
        Bitmap scaled = Bitmap.createScaledBitmap(source, size, size, true);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        scaled.compress(Bitmap.CompressFormat.PNG, 100, out);
        if (scaled != source) scaled.recycle();
        source.recycle();
        return out.toByteArray();
    }

    /**
     * Rasterises a top view of the extruding moves. Two passes: bounds, then draw.
     */
    public static byte[] render(File gcode, int size) {
        Bounds bounds = measure(gcode);
        if (bounds == null) return solid(size);

        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(BACKGROUND);

        float margin = size * 0.08f;
        float usable = size - 2 * margin;
        float scaleX = usable / Math.max(1f, bounds.maxX - bounds.minX);
        float scaleY = usable / Math.max(1f, bounds.maxY - bounds.minY);
        float scale = Math.min(scaleX, scaleY);
        float offsetX = margin + (usable - (bounds.maxX - bounds.minX) * scale) / 2f;
        float offsetY = margin + (usable - (bounds.maxY - bounds.minY) * scale) / 2f;

        // Plate footprint, when the extremes describe it.
        Paint platePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        platePaint.setColor(PLATE);
        platePaint.setStyle(Paint.Style.FILL);
        canvas.drawRect(
                offsetX + (bounds.minX - bounds.minX) * scale,
                offsetY + (bounds.minY - bounds.minY) * scale,
                offsetX + (bounds.maxX - bounds.minX) * scale,
                offsetY + (bounds.maxY - bounds.minY) * scale,
                platePaint);

        Paint pathPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pathPaint.setColor(PATH_COLOR);
        pathPaint.setStyle(Paint.Style.STROKE);
        pathPaint.setStrokeWidth(Math.max(1f, size / 300f));
        pathPaint.setStrokeCap(Paint.Cap.ROUND);

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(gcode), StandardCharsets.ISO_8859_1), 1 << 16)) {
            String line;
            float x = 0f, y = 0f;
            boolean relative = false;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == ';') {
                    if (line.startsWith("M83")) relative = true;
                    else if (line.startsWith("M82")) relative = false;
                    continue;
                }
                if (line.startsWith("G0") || line.startsWith("G1")) {
                    float nx = parse(line, 'X');
                    float ny = parse(line, 'Y');
                    float ne = parse(line, 'E');
                    boolean extrudes = !Float.isNaN(ne) && (relative ? ne > 0 : !Float.isNaN(ne));
                    float px = x, py = y;
                    if (!Float.isNaN(nx)) x = nx;
                    if (!Float.isNaN(ny)) y = ny;
                    if (extrudes && (!Float.isNaN(nx) || !Float.isNaN(ny))) {
                        canvas.drawLine(
                                offsetX + (px - bounds.minX) * scale,
                                offsetY + (py - bounds.minY) * scale,
                                offsetX + (x - bounds.minX) * scale,
                                offsetY + (y - bounds.minY) * scale,
                                pathPaint);
                    }
                }
            }
        } catch (IOException e) {
            bitmap.recycle();
            return solid(size);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        bitmap.recycle();
        return out.toByteArray();
    }

    private static final class Bounds {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
    }

    /** Plate bounds of the extruding moves: {minX, minY, maxX, maxY}. */
    public static float[] bounds(File gcode) {
        Bounds b = measure(gcode);
        if (b == null) return new float[]{0f, 0f, 0f, 0f};
        return new float[]{b.minX, b.minY, b.maxX, b.maxY};
    }

    private static Bounds measure(File gcode) {
        Bounds bounds = new Bounds();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(gcode), StandardCharsets.ISO_8859_1), 1 << 16)) {
            String line;
            boolean any = false;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == ';') continue;
                if (!line.startsWith("G0") && !line.startsWith("G1")) continue;
                float ne = parse(line, 'E');
                if (Float.isNaN(ne) || ne <= 0) continue;
                float x = parse(line, 'X');
                float y = parse(line, 'Y');
                if (Float.isNaN(x) || Float.isNaN(y)) continue;
                bounds.minX = Math.min(bounds.minX, x);
                bounds.maxX = Math.max(bounds.maxX, x);
                bounds.minY = Math.min(bounds.minY, y);
                bounds.maxY = Math.max(bounds.maxY, y);
                any = true;
            }
            if (!any) return null;
        } catch (IOException e) {
            return null;
        }
        return bounds;
    }

    private static byte[] solid(int size) {
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.rgb(0x12, 0x16, 0x1C));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        bitmap.recycle();
        return out.toByteArray();
    }

    private static float parse(String line, char letter) {
        for (int i = 1; i < line.length() - 1; i++) {
            if (line.charAt(i) != letter) continue;
            char prev = line.charAt(i - 1);
            if (prev != ' ' && prev != '\t') continue;
            char next = line.charAt(i + 1);
            if (!Character.isDigit(next) && next != '-' && next != '.') continue;
            int end = i + 1;
            while (end < line.length()) {
                char c = line.charAt(end);
                if (Character.isDigit(c) || c == '.' || c == '-' || c == '+') end++;
                else break;
            }
            try {
                return Float.parseFloat(String.format(Locale.US, "%s", line.substring(i + 1, end)));
            } catch (NumberFormatException e) {
                return Float.NaN;
            }
        }
        return Float.NaN;
    }
}
