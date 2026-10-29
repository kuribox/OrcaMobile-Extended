package ru.ytkab0bp.slicebeam.server;

import android.text.TextUtils;

import java.io.File;
import java.lang.ref.WeakReference;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ru.ytkab0bp.slicebeam.SliceBeam;
import ru.ytkab0bp.slicebeam.config.ConfigObject;
import ru.ytkab0bp.slicebeam.fragment.BedFragment;
import ru.ytkab0bp.slicebeam.slic3r.Model;
import ru.ytkab0bp.slicebeam.utils.GCode3mfPackager;
import ru.ytkab0bp.slicebeam.utils.GCodeStats;
import ru.ytkab0bp.slicebeam.utils.GCodeThumbnail;
import ru.ytkab0bp.slicebeam.utils.HostTransfer;
import ru.ytkab0bp.slicebeam.utils.ViewUtils;
import ru.ytkab0bp.slicebeam.utils.Vec3d;

/** {@link PlateBridge} on top of the live bed screen. */
public class BedPlateBridge implements PlateBridge {

    private static final Pattern TIME = Pattern.compile("(?i)^;\\s*estimated printing time[^=]*=\\s*(.*)$");
    private static final Pattern WEIGHT = Pattern.compile("(?i)^;\\s*total filament weight \\[g\\]\\s*=\\s*(.*)$");
    private static final Pattern DURATION = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*([dhms])", Pattern.CASE_INSENSITIVE);

    private final WeakReference<BedFragment> bed;
    private final List<String> names = new ArrayList<>();
    private final AtomicBoolean busy = new AtomicBoolean();

    public BedPlateBridge(BedFragment bed) {
        this.bed = new WeakReference<>(bed);
        seedNames();
    }

    private BedFragment fragment() {
        return bed.get();
    }

    private void seedNames() {
        BedFragment f = fragment();
        Model model = f == null ? null : f.getCurrentModel();
        if (model == null) return;
        for (int i = 0; i < model.getObjectsCount(); i++) {
            names.add("Pieza " + (i + 1));
        }
    }

    @Override
    public boolean isReady() {
        BedFragment f = fragment();
        return f != null && f.getCurrentModel() != null;
    }

    @Override
    public String statusJson() {
        BedFragment f = fragment();
        Model model = f == null ? null : f.getCurrentModel();
        ConfigObject printerPreset = SliceBeam.CONFIG == null
                ? null : SliceBeam.CONFIG.findPrinter(SliceBeam.CONFIG.presets.get("printer"));

        StringBuilder sb = new StringBuilder("{");
        sb.append("\"parts\":[");
        if (model != null) {
            for (int i = 0; i < model.getObjectsCount(); i++) {
                if (i > 0) sb.append(',');
                sb.append("{\"index\":").append(i);
                sb.append(",\"name\":\"").append(SliceJob.escape(nameAt(i))).append('"');
                sb.append(",\"sizeMm\":\"").append(SliceJob.escape(sizeOf(model, i))).append('"');
                sb.append('}');
            }
        }
        sb.append("],\"printers\":[");
        if (SliceBeam.CONFIG != null) {
            for (int i = 0; i < SliceBeam.CONFIG.printerConfigs.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append('"').append(SliceJob.escape(
                        SliceBeam.CONFIG.printerConfigs.get(i).getTitle())).append('"');
            }
        }
        sb.append("],\"filaments\":[");
        if (SliceBeam.CONFIG != null) {
            for (int i = 0; i < SliceBeam.CONFIG.filamentConfigs.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append('"').append(SliceJob.escape(
                        SliceBeam.CONFIG.filamentConfigs.get(i).getTitle())).append('"');
            }
        }
        sb.append(']');
        sb.append(",\"printer\":\"").append(SliceJob.escape(
                SliceBeam.CONFIG == null ? "" : SliceBeam.CONFIG.presets.get("printer"))).append('"');
        sb.append(",\"filament\":\"").append(SliceJob.escape(
                SliceBeam.CONFIG == null ? "" : SliceBeam.CONFIG.presets.get("filament"))).append('"');
        sb.append(",\"bed\":\"").append(SliceJob.escape(
                printerPreset == null ? "" : printerPreset.get("printable_area"))).append('"');
        sb.append(",\"options\":{");
        Map<String, String> live = SliceBeam.LIVE_DIFF_PRINT.values;
        sb.append("\"supports\":\"").append(supportsValue(live.get("enable_support"), live.get("support_type"))).append('"');
        sb.append(",\"brim\":\"").append(brimValue(live.get("brim_type"))).append('"');
        sb.append(",\"skirt\":\"").append("0".equals(live.get("skirt_loops")) ? "no" : "yes").append('"');
        sb.append(",\"quality\":\"").append(SliceJob.escape(
                live.containsKey("layer_height") ? live.get("layer_height") : "0.20")).append('"');
        sb.append(",\"infill\":\"").append(SliceJob.escape(
                live.containsKey("sparse_infill_density") ? live.get("sparse_infill_density") : "25%")).append('"');
        sb.append("}}");
        return sb.toString();
    }

    private static String supportsValue(String enabled, String type) {
        if ("0".equals(enabled)) return "none";
        if (type != null && type.startsWith("tree")) return "tree";
        return "normal";
    }

    private static String brimValue(String type) {
        if (type == null || "no_brim".equals(type)) return "none";
        if ("outer_only".equals(type)) return "outer";
        return "auto";
    }

    private String nameAt(int index) {
        if (index >= 0 && index < names.size()) return names.get(index);
        return "Pieza " + (index + 1);
    }

    private static String sizeOf(Model model, int index) {
        try {
            Vec3d min = new Vec3d();
            Vec3d max = new Vec3d();
            model.getBoundingBoxExact(index, min, max);
            return String.format(Locale.getDefault(), "%d × %d × %d mm",
                    Math.round(max.x - min.x), Math.round(max.y - min.y), Math.round(max.z - min.z));
        } catch (Throwable t) {
            return "";
        }
    }

    @Override
    public byte[] previewPng(int maxWidth) {
        File gcode = lastGcode();
        if (gcode != null && gcode.isFile()) {
            byte[] png = GCodeThumbnail.render(gcode, maxWidth);
            if (png != null) return png;
        }
        BedFragment f = fragment();
        return f == null ? null : f.capturePlatePng(maxWidth);
    }

    @Override
    public int addPart(File file) throws Exception {
        BedFragment f = fragment();
        if (f == null) throw new IllegalStateException("app_closed");
        if (!busy.compareAndSet(false, true)) throw new IllegalStateException("busy");
        try {
            f.loadModel(file);
            names.add(file.getName());
            arrange();
            Model loaded = f.getCurrentModel();
            int count = loaded == null ? 0 : loaded.getObjectsCount();
            if (count == 0) throw new IllegalStateException("model_not_loaded");
            return count - 1;
        } finally {
            busy.set(false);
        }
    }

    @Override
    public void removePart(int index) {
        BedFragment f = fragment();
        Model model = f == null ? null : f.getCurrentModel();
        if (model == null) return;
        model.deleteObject(index);
        model.resetBoundingBox();
        if (index >= 0 && index < names.size()) names.remove(index);
        f.invalidateModel();
    }

    @Override
    public void arrange() {
        BedFragment f = fragment();
        Model model = f == null ? null : f.getCurrentModel();
        if (model == null || f.getGlView() == null) return;
        try {
            if (f.getGlView().getRenderer().getBed() != null) {
                f.getGlView().getRenderer().getBed().arrange(model);
            }
            model.resetBoundingBox();
            f.invalidateModel();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void applyOptions(Map<String, String> options) {
        if (options == null) return;
        String printer = options.get("printer");
        String filament = options.get("filament");
        if (SliceBeam.CONFIG != null) {
            if (!TextUtils.isEmpty(printer)) SliceBeam.CONFIG.presets.put("printer", printer);
            if (!TextUtils.isEmpty(filament)) SliceBeam.CONFIG.presets.put("filament", filament);
            if (!TextUtils.isEmpty(printer) || !TextUtils.isEmpty(filament)) {
                try {
                    SliceBeam.ensureCompatibleSelection();
                    SliceBeam.saveConfig();
                } catch (Throwable ignored) {
                }
            }
        }

        Map<String, String> live = SliceBeam.LIVE_DIFF_PRINT.values;
        String supports = options.get("supports");
        if (supports != null) {
            if ("none".equals(supports)) {
                live.put("enable_support", "0");
            } else {
                live.put("enable_support", "1");
                live.put("support_type", "tree".equals(supports) ? "tree(auto)" : "normal(auto)");
            }
        }
        String brim = options.get("brim");
        if (brim != null) {
            live.put("brim_type", "none".equals(brim) ? "no_brim"
                    : "outer".equals(brim) ? "outer_only" : "auto_brim");
        }
        String skirt = options.get("skirt");
        if (skirt != null) {
            live.put("skirt_loops", "no".equals(skirt) ? "0" : "1");
        }
        String quality = options.get("quality");
        if (!TextUtils.isEmpty(quality)) {
            live.put("layer_height", quality);
        }
        String infill = options.get("infill");
        if (!TextUtils.isEmpty(infill)) {
            live.put("sparse_infill_density", infill);
        }
    }

    @Override
    public SliceJob.Stats slice(Progress progress) throws Exception {
        BedFragment f = fragment();
        Model model = f == null ? null : f.getCurrentModel();
        if (model == null) throw new IllegalStateException("no_model");
        if (model.getObjectsCount() == 0) throw new IllegalStateException("empty_plate");

        SliceBeam.ensureCompatibleSelection();
        SliceBeam.genCurrentConfig();
        File config = SliceBeam.getCurrentConfigFile();
        File gcode = BedFragment.getTempGCodePath();

        model.slice(config.getAbsolutePath(), gcode.getAbsolutePath(),
                (percent, text) -> progress.onProgress(percent, text));

        SliceJob.Stats stats = new SliceJob.Stats();
        stats.size = gcode.length();
        stats.sha256 = sha256(gcode);
        readComments(gcode, stats);
        try {
            GCodeStats measured = GCodeStats.analyze(gcode);
            stats.layers = measured.layers;
            stats.speed = measured.speed.hasData() ? measured.speed.avg : 0;
        } catch (Throwable ignored) {
        }

        // Show it in the phone too, exactly like a manual slice.
        ViewUtils.postOnMainThread(() -> {
            try {
                f.loadGCode(gcode);
            } catch (Throwable ignored) {
            }
        });
        return stats;
    }

    private void readComments(File gcode, SliceJob.Stats stats) {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(gcode),
                        java.nio.charset.StandardCharsets.ISO_8859_1), 1 << 16)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith(";")) continue;
                Matcher time = TIME.matcher(line);
                if (time.matches() && stats.seconds == 0) stats.seconds = parseDuration(time.group(1));
                Matcher weight = WEIGHT.matcher(line);
                if (weight.matches() && stats.grams == 0) {
                    try {
                        stats.grams = Double.parseDouble(weight.group(1).trim().replace("\"", ""));
                    } catch (NumberFormatException ignored) {
                    }
                }
                if (stats.seconds > 0 && stats.grams > 0) break;
            }
        } catch (Throwable ignored) {
        }
    }

    private static int parseDuration(String text) {
        if (text == null) return 0;
        Matcher matcher = DURATION.matcher(text);
        int total = 0;
        while (matcher.find()) {
            double value = Double.parseDouble(matcher.group(1));
            switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
                case "d": total += value * 86400; break;
                case "h": total += value * 3600; break;
                case "m": total += value * 60; break;
                default: total += value; break;
            }
        }
        return total;
    }

    private static String sha256(File file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (java.io.InputStream in = new java.io.FileInputStream(file)) {
                byte[] buffer = new byte[1 << 16];
                int read;
                while ((read = in.read(buffer)) > 0) digest.update(buffer, 0, read);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private File lastGcode() {
        File file = BedFragment.getTempGCodePath();
        return file != null && file.isFile() ? file : null;
    }

    @Override
    public File gcodeFile() {
        return lastGcode();
    }

    @Override
    public File packageFile() throws Exception {
        File gcode = lastGcode();
        if (gcode == null) throw new IllegalStateException("no_slice");
        ConfigObject printer = SliceBeam.CONFIG == null ? null
                : SliceBeam.CONFIG.findPrinter(SliceBeam.CONFIG.presets.get("printer"));
        File dir = new File(SliceBeam.INSTANCE.getCacheDir(), "server");
        dir.mkdirs();
        File target = new File(dir, "orcam_package.gcode.3mf");
        GCode3mfPackager.create(gcode, target, printer, null);
        return target;
    }

    @Override
    public String sendToPrinter(boolean print, boolean use3mf) {
        BedFragment f = fragment();
        if (f == null) throw new IllegalStateException("app_closed");
        final String[] message = {""};
        final boolean[] ok = {false};
        HostTransfer.send(f, print, use3mf, (success, text) -> {
            ok[0] = success;
            message[0] = text;
        });
        if (!ok[0]) throw new IllegalStateException(message[0] == null ? "send_failed" : message[0]);
        return message[0] == null ? "ok" : message[0];
    }
}
