package ru.ytkab0bp.slicebeam.utils;

import ru.ytkab0bp.slicebeam.config.ConfigObject;
import ru.ytkab0bp.slicebeam.slic3r.GCodeProcessorResult;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Builds the {@code .gcode.3mf} package printers expect.
 *
 * <p>The layout follows OrcaSlicer's own exporter (src/libslic3r/Format/bbs_3mf.cpp) instead of a
 * hand-made zip: identical part names, content types and relationship ids
 * ({@code rel-1}/{@code rel-2}/{@code rel-4}/{@code rel-5}), the small companion thumbnail, plate and
 * object metadata, and a flat {@code project_settings.config}.
 *
 * <p>When a model 3MF is supplied (exported from the loaded model by the engine), its
 * {@code 3D/3dmodel.model} and remaining parts are carried over, so the package ships the real
 * geometry next to the G-code rather than an empty model.
 */
public final class GCode3mfPackager {

    private static final String CONTENT_TYPES = "[Content_Types].xml";
    private static final String ROOT_RELS = "_rels/.rels";
    private static final String MODEL_FILE = "3D/3dmodel.model";

    private static final int PLATE = 1;
    /** The working Flashforge package (OrcaSlicer 2.4.2, AD5X) ships 512x512 plate images. */
    private static final int THUMBNAIL_SIZE = 512;
    /** PLATE_THUMBNAIL_SMALL_WIDTH in bbs_3mf.hpp: Orca writes a 128x128 companion image. */
    private static final int SMALL_THUMBNAIL_SIZE = 128;

    private static final Pattern SETTING_LINE = Pattern.compile("^;\\s*([^=]+?)\\s*=\\s*(.*?)\\s*$");
    private static final Pattern LAYER_LINE = Pattern.compile("(?i)^;\\s*total layer number\\s*:\\s*(\\d+)\\s*$");
    private static final Pattern TOOL_LINE = Pattern.compile("^T(\\d+)(?:\\s|$)");
    private static final Pattern DURATION_PART = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*([dhms])", Pattern.CASE_INSENSITIVE);
    private static final Pattern ESTIMATED_TIME =
            Pattern.compile("(?i)^;\\s*estimated printing time[^=]*=\\s*(.*)$");
    private static final Pattern FILAMENT_USED_MM = Pattern.compile("(?i)^;\\s*filament used \\[mm\\]\\s*=\\s*(.*)$");
    private static final Pattern FILAMENT_USED_G = Pattern.compile("(?i)^;\\s*filament used \\[g\\]\\s*=\\s*(.*)$");
    private static final Pattern MODEL_OBJECT = Pattern.compile("<object\\b[^>]*\\bid\\s*=\\s*\"(\\d+)\"[^>]*>");
    private static final Pattern META_NAME = Pattern.compile("<metadata\\s+name\\s*=\\s*\"name\"\\s+value\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern OBJECT_ATTR_NAME = Pattern.compile("\\bname\\s*=\\s*\"([^\"]*)\"");

    public static final class PackageSummary {
        public final int layers;
        public final int durationSeconds;
        public final int filamentCount;
        public final int thumbnailBytes;
        public final int objects;
        public final boolean hasGeometry;

        PackageSummary(int layers, int durationSeconds, int filamentCount, int thumbnailBytes,
                       int objects, boolean hasGeometry) {
            this.layers = layers;
            this.durationSeconds = durationSeconds;
            this.filamentCount = filamentCount;
            this.thumbnailBytes = thumbnailBytes;
            this.objects = objects;
            this.hasGeometry = hasGeometry;
        }
    }

    private static final class PrintMetadata {
        final Map<String, String> settings = new LinkedHashMap<>();
        int layers;
        int seconds;
        float nozzle = 0.4f;
        float layerHeight = 0.2f;
        String objectName = "plate_1";
        final List<String> materials = new ArrayList<>();
        final List<String> colors = new ArrayList<>();
        final List<Double> usedMeters = new ArrayList<>();
        final List<Double> usedGrams = new ArrayList<>();
        final List<Integer> sequence = new ArrayList<>();
    }

    private GCode3mfPackager() {}

    /**
     * @param sourceGcode sliced G-code
     * @param destination target .gcode.3mf
     * @param printer     printer preset (names, filaments)
     * @param gcodeResult slice result, may be null
     * @param model3mf    optional 3MF exported from the loaded model (carries the geometry)
     */
    public static PackageSummary create(File sourceGcode,
                                        File destination,
                                        ConfigObject printer,
                                        GCodeProcessorResult gcodeResult) throws Exception {
        if (sourceGcode == null || !sourceGcode.isFile() || sourceGcode.length() == 0L) {
            throw new IllegalArgumentException("The sliced G-code file is missing or empty.");
        }

        PrintMetadata meta = readMetadata(sourceGcode);

        byte[] platePng = GCodeThumbnail.extract(sourceGcode);
        if (platePng == null) {
            // The engine exports G-code with a null thumbnail callback, so render one.
            platePng = GCodeThumbnail.render(sourceGcode, THUMBNAIL_SIZE);
        }
        byte[] smallPng = GCodeThumbnail.scaleTo(platePng, SMALL_THUMBNAIL_SIZE);
        if (smallPng == null) smallPng = platePng;

        float[] bbox = GCodeThumbnail.bounds(sourceGcode);

        File parent = destination.getParentFile();
        if (parent != null) parent.mkdirs();
        if (destination.exists() && !destination.delete()) {
            throw new IOException("Could not replace the previous .gcode.3mf file.");
        }

        String gcodePath = "Metadata/plate_" + PLATE + ".gcode";

        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(destination))) {
            writeText(zip, CONTENT_TYPES, contentTypesXml());
            writeText(zip, ROOT_RELS, rootRelationshipsXml());

            writeText(zip, MODEL_FILE, modelXml());

            // The accepted package ships four 512x512 plate images plus the 128x128 companion; all of
            // them are referenced from model_settings.config, so none may be missing.
            writeBytes(zip, "Metadata/plate_" + PLATE + ".png", platePng);
            writeBytes(zip, "Metadata/plate_" + PLATE + "_small.png", smallPng);
            writeBytes(zip, "Metadata/plate_no_light_" + PLATE + ".png", platePng);
            writeBytes(zip, "Metadata/top_" + PLATE + ".png", platePng);
            writeBytes(zip, "Metadata/pick_" + PLATE + ".png", platePng);
            writeText(zip, "Metadata/plate_" + PLATE + ".json", plateJson(meta, bbox));
            writeText(zip, "Metadata/project_settings.config", projectSettings(meta, printer));

            writeText(zip, "Metadata/plate_" + PLATE + ".gcode.md5", digest(sourceGcode));
            zip.putNextEntry(new ZipEntry(gcodePath));
            try (FileInputStream input = new FileInputStream(sourceGcode)) {
                byte[] buffer = new byte[128 * 1024];
                int len;
                while ((len = input.read(buffer)) > 0) {
                    zip.write(buffer, 0, len);
                }
            }
            zip.closeEntry();

            writeText(zip, "Metadata/model_settings.config", modelSettingsXml());
            writeText(zip, "Metadata/_rels/model_settings.config.rels",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">\n"
                            + " <Relationship Target=\"/" + gcodePath + "\" Id=\"rel-1\" "
                            + "Type=\"http://schemas.bambulab.com/package/2021/gcode\"/>\n"
                            + "</Relationships>\n");
            writeText(zip, "Metadata/slice_info.config", sliceInfoXml(meta, printer, bbox));
            writeText(zip, "Metadata/filament_sequence.json", sequenceJson(meta));
        } catch (Exception e) {
            destination.delete();
            throw e;
        }

        return new PackageSummary(meta.layers, meta.seconds, meta.materials.size(), platePng.length,
                1, false);
    }

    /** Checks the parts a strict importer looks for. */
    public static boolean verify(File packageFile) {
        try (ZipFile zip = new ZipFile(packageFile)) {
            String[] required = {
                    CONTENT_TYPES, ROOT_RELS, MODEL_FILE,
                    "Metadata/plate_" + PLATE + ".png",
                    "Metadata/plate_" + PLATE + "_small.png",
                    "Metadata/plate_no_light_" + PLATE + ".png",
                    "Metadata/top_" + PLATE + ".png",
                    "Metadata/pick_" + PLATE + ".png",
                    "Metadata/plate_" + PLATE + ".json",
                    "Metadata/plate_" + PLATE + ".gcode",
                    "Metadata/model_settings.config",
                    "Metadata/_rels/model_settings.config.rels",
                    "Metadata/project_settings.config",
                    "Metadata/slice_info.config"
            };
            for (String entry : required) {
                if (zip.getEntry(entry) == null) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- metadata

    private static PrintMetadata readMetadata(File sourceGcode) throws IOException {
        File gcodeFile = sourceGcode;
        PrintMetadata meta = new PrintMetadata();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(gcodeFile), StandardCharsets.ISO_8859_1), 1 << 16)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(";")) {
                    Matcher setting = SETTING_LINE.matcher(line);
                    if (setting.matches()) {
                        meta.settings.put(setting.group(1).trim(), setting.group(2).trim());
                    }
                    Matcher layers = LAYER_LINE.matcher(line);
                    if (layers.matches()) {
                        meta.layers = Integer.parseInt(layers.group(1));
                    }
                    Matcher time = ESTIMATED_TIME.matcher(line);
                    if (time.matches() && meta.seconds <= 0) {
                        meta.seconds = parseDuration(time.group(1));
                    }
                    Matcher mm = FILAMENT_USED_MM.matcher(line);
                    if (mm.matches()) {
                        meta.usedMeters.clear();
                        for (String part : splitValues(mm.group(1))) {
                            try {
                                meta.usedMeters.add(Double.parseDouble(part) / 1000.0);
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                    Matcher grams = FILAMENT_USED_G.matcher(line);
                    if (grams.matches()) {
                        meta.usedGrams.clear();
                        for (String part : splitValues(grams.group(1))) {
                            try {
                                meta.usedGrams.add(Double.parseDouble(part));
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                    continue;
                }

                Matcher tool = TOOL_LINE.matcher(line);
                if (tool.matches()) {
                    int index = Integer.parseInt(tool.group(1));
                    if (!meta.sequence.contains(index)) meta.sequence.add(index);
                }
            }
        }

        String nozzle = firstValue(meta.settings.get("nozzle_diameter"));
        if (nozzle != null) {
            try {
                meta.nozzle = Float.parseFloat(nozzle);
            } catch (NumberFormatException ignored) {
            }
        }

        String height = firstValue(meta.settings.get("layer_height"));
        if (height != null) {
            try {
                meta.layerHeight = Float.parseFloat(height);
            } catch (NumberFormatException ignored) {
            }
        }
        meta.objectName = sourceGcode.getName().replaceFirst("(?i)\\.gcode$", "");

        if (meta.layers <= 0) meta.layers = 1;

        String types = firstValue(meta.settings.get("filament_type"));
        String colours = firstValue(meta.settings.get("filament_colour"));
        String[] typeList = splitValues(meta.settings.getOrDefault("filament_type", "PLA"));
        String[] colourList = splitValues(meta.settings.getOrDefault("filament_colour", "#FFFFFF"));

        int count = Math.max(1, Math.max(meta.usedMeters.size(), Math.max(typeList.length, colourList.length)));
        for (int i = 0; i < count; i++) {
            meta.materials.add(typeList.length > 0 ? typeList[i % typeList.length] : "PLA");
            meta.colors.add(colourList.length > 0 ? colourList[i % colourList.length] : "#FFFFFF");
        }
        while (meta.usedMeters.size() < count) meta.usedMeters.add(0.0);
        while (meta.usedGrams.size() < count) meta.usedGrams.add(0.0);

        // Silence unused warnings: kept for readability of the parse above.
        if (types == null && colours == null) {
            meta.settings.putIfAbsent("filament_type", "PLA");
        }
        return meta;
    }

    private static String[] splitValues(String raw) {
        if (raw == null) return new String[0];
        String cleaned = raw.replace("\"", "").trim();
        if (cleaned.isEmpty()) return new String[0];
        String[] parts = cleaned.split(";|,");
        for (int i = 0; i < parts.length; i++) parts[i] = parts[i].trim();
        return parts;
    }

    private static String firstValue(String raw) {
        String[] parts = splitValues(raw);
        return parts.length > 0 ? parts[0] : null;
    }

    private static int parseDuration(String text) {
        if (text == null) return 0;
        Matcher matcher = DURATION_PART.matcher(text);
        int seconds = 0;
        while (matcher.find()) {
            double value = Double.parseDouble(matcher.group(1));
            switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
                case "d": seconds += value * 86400; break;
                case "h": seconds += value * 3600; break;
                case "m": seconds += value * 60; break;
                default: seconds += value; break;
            }
        }
        return seconds;
    }

    // ---------------------------------------------------------------- archive parts

    /** Same parts and MIME types as _add_content_types_file_to_archive(). */
    private static String contentTypesXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">\n"
                + " <Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>\n"
                + " <Default Extension=\"model\" ContentType=\"application/vnd.ms-package.3dmanufacturing-3dmodel+xml\"/>\n"
                + " <Default Extension=\"png\" ContentType=\"image/png\"/>\n"
                + " <Default Extension=\"gcode\" ContentType=\"text/x.gcode\"/>\n"
                + "</Types>";
    }

    /** Same relationship ids and types as _add_relationships_file_to_archive(). */
    private static String rootRelationshipsXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">\n"
                + " <Relationship Target=\"/" + MODEL_FILE + "\" Id=\"rel-1\" "
                + "Type=\"http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel\"/>\n"
                + " <Relationship Target=\"/Metadata/plate_" + PLATE + ".png\" Id=\"rel-2\" "
                + "Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/thumbnail\"/>\n"
                + " <Relationship Target=\"/Metadata/plate_" + PLATE + ".png\" Id=\"rel-4\" "
                + "Type=\"http://schemas.bambulab.com/package/2021/cover-thumbnail-middle\"/>\n"
                + " <Relationship Target=\"/Metadata/plate_" + PLATE + "_small.png\" Id=\"rel-5\" "
                + "Type=\"http://schemas.bambulab.com/package/2021/cover-thumbnail-small\"/>\n"
                + "</Relationships>";
    }

    private static String modelXml() {
        // The reference .gcode.3mf that the printer accepts carries no geometry at all: <resources> is
        // empty and the plate lives in Metadata/. Keep the same shape.
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<model unit=\"millimeter\" xml:lang=\"en-US\" "
                + "xmlns=\"http://schemas.microsoft.com/3dmanufacturing/core/2015/02\" "
                + "xmlns:BambuStudio=\"http://schemas.bambulab.com/package/2021\" "
                + "xmlns:p=\"http://schemas.microsoft.com/3dmanufacturing/production/2015/06\" requiredextensions=\"p\">\n"
                + " <metadata name=\"Application\">OrcaSlicer Mobile</metadata>\n"
                + " <metadata name=\"OrcaSlicer\">OrcaSlicer Mobile</metadata>\n"
                + " <metadata name=\"BambuStudio:3mfVersion\">1</metadata>\n"
                + " <metadata name=\"Thumbnail_Middle\">/Metadata/plate_" + PLATE + ".png</metadata>\n"
                + " <metadata name=\"Thumbnail_Small\">/Metadata/plate_" + PLATE + "_small.png</metadata>\n"
                + " <resources>\n </resources>\n <build/>\n</model>\n";
    }



    /** Same schema as the accepted package: bbox_all/bbox_objects plus plate hints. */
    private static String plateJson(PrintMetadata meta, float[] bbox) {
        StringBuilder sb = new StringBuilder("{\"bbox_all\":[");
        for (int i = 0; i < 4; i++) {
            if (i > 0) sb.append(",");
            sb.append(fmtCoord(bbox[i]));
        }
        sb.append("],\"bbox_objects\":[{\"area\":");
        sb.append(fmtCoord(Math.max(0f, (bbox[2] - bbox[0]) * (bbox[3] - bbox[1]))));
        sb.append(",\"bbox\":[");
        for (int i = 0; i < 4; i++) {
            if (i > 0) sb.append(",");
            sb.append(fmtCoord(bbox[i]));
        }
        sb.append("],\"id\":1,\"layer_height\":").append(fmtCoord(meta.layerHeight));
        sb.append(",\"name\":\"").append(escapeJson(meta.objectName)).append("\"}],");
        sb.append("\"bed_type\":\"hot_plate\",\"filament_colors\":[");
        for (int i = 0; i < meta.colors.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(escapeJson(meta.colors.get(i))).append("\"");
        }
        sb.append("],\"filament_ids\":[");
        for (int i = 0; i < meta.colors.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(0);
        }
        sb.append("],\"first_extruder\":0");
        sb.append(",\"is_seq_print\":false,\"nozzle_diameter\":").append(fmtCoord(meta.nozzle));
        sb.append(",\"version\":2}");
        return sb.toString();
    }

    private static String repeat(String value, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.max(1, count); i++) {
            if (i > 0) sb.append(" ");
            sb.append(value);
        }
        return sb.toString();
    }

    private static String fmtCoord(float value) {
        return String.format(Locale.US, "%.6f", value);
    }



    private static String projectSettings(PrintMetadata meta, ConfigObject printer) {
        StringBuilder sb = new StringBuilder("{\n");
        int written = 0;
        for (Map.Entry<String, String> entry : meta.settings.entrySet()) {
            if (entry.getKey().isEmpty() || entry.getKey().contains("thumbnail")) continue;
            if (written++ > 0) sb.append(",\n");
            sb.append("  \"").append(escapeJson(entry.getKey())).append("\": ").append(jsonValue(entry.getValue()));
        }
        if (printer != null && printer.values != null) {
            for (Map.Entry<String, String> entry : printer.values.entrySet()) {
                if (meta.settings.containsKey(entry.getKey())) continue;
                if (written++ > 0) sb.append(",\n");
                sb.append("  \"").append(escapeJson(entry.getKey())).append("\": ").append(jsonValue(entry.getValue()));
            }
        }
        sb.append("\n}\n");
        return sb.toString();
    }

    private static String jsonValue(String raw) {
        if (raw == null) return "\"\"";
        String value = raw.trim();
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
            return value.toLowerCase(Locale.ROOT);
        }
        try {
            Double.parseDouble(value);
            return value;
        } catch (NumberFormatException ignored) {
        }
        return "\"" + escapeJson(raw) + "\"";
    }

    private static String modelSettingsXml() {
        // Mirrors the accepted file: only <plate>, and every file it names must exist in the archive.
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<config>\n  <plate>\n");
        sb.append("    <metadata key=\"plater_id\" value=\"").append(PLATE).append("\"/>\n");
        sb.append("    <metadata key=\"plater_name\" value=\"\"/>\n");
        sb.append("    <metadata key=\"locked\" value=\"false\"/>\n");
        sb.append("    <metadata key=\"filament_map_mode\" value=\"Auto For Flush\"/>\n");
        sb.append("    <metadata key=\"filament_maps\" value=\"1\"/>\n");
        sb.append("    <metadata key=\"gcode_file\" value=\"Metadata/plate_").append(PLATE).append(".gcode\"/>\n");
        sb.append("    <metadata key=\"thumbnail_file\" value=\"Metadata/plate_").append(PLATE).append(".png\"/>\n");
        sb.append("    <metadata key=\"thumbnail_no_light_file\" value=\"Metadata/plate_no_light_").append(PLATE).append(".png\"/>\n");
        sb.append("    <metadata key=\"top_file\" value=\"Metadata/top_").append(PLATE).append(".png\"/>\n");
        sb.append("    <metadata key=\"pick_file\" value=\"Metadata/pick_").append(PLATE).append(".png\"/>\n");
        sb.append("    <metadata key=\"pattern_bbox_file\" value=\"Metadata/plate_").append(PLATE).append(".json\"/>\n");
        sb.append("  </plate>\n</config>\n");
        return sb.toString();
    }



    /**
     * Root element is {@code <config>}, not {@code <slice_info>}: that is what the package accepted by
     * the printer carries, and what its importer looks for.
     */
    private static String sliceInfoXml(PrintMetadata meta, ConfigObject printer, float[] bbox) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<config>\n");
        sb.append("  <header>\n");
        sb.append("    <header_item key=\"X-BBL-Client-Type\" value=\"slicer\"/>\n");
        sb.append("    <header_item key=\"X-BBL-Client-Version\" value=\"OrcaSlicer Mobile\"/>\n");
        sb.append("    <header_item key=\"OrcaSlicer-Version\" value=\"OrcaSlicer Mobile\"/>\n");
        sb.append("  </header>\n");
        sb.append("  <plate>\n");
        sb.append("    <metadata key=\"index\" value=\"").append(PLATE).append("\"/>\n");
        sb.append("    <metadata key=\"extruder_type\" value=\"0\"/>\n");
        sb.append("    <metadata key=\"nozzle_volume_type\" value=\"0\"/>\n");
        if (printer != null) {
            sb.append("    <metadata key=\"printer_model_id\" value=\"")
                    .append(escapeXml(printer.getTitle())).append("\"/>\n");
        }
        sb.append("    <metadata key=\"nozzle_diameters\" value=\"")
                .append(String.format(Locale.US, "%.1f", meta.nozzle)).append("\"/>\n");
        sb.append("    <metadata key=\"timelapse_type\" value=\"0\"/>\n");
        sb.append("    <metadata key=\"prediction\" value=\"").append(meta.seconds).append("\"/>\n");
        sb.append("    <metadata key=\"weight\" value=\"").append(fmt(sum(meta.usedGrams))).append("\"/>\n");
        sb.append("    <metadata key=\"outside\" value=\"false\"/>\n");
        sb.append("    <metadata key=\"support_used\" value=\"false\"/>\n");
        sb.append("    <metadata key=\"label_object_enabled\" value=\"true\"/>\n");
        sb.append("    <metadata key=\"enable_filament_dynamic_map\" value=\"false\"/>\n");
        sb.append("    <metadata key=\"has_filament_switcher\" value=\"false\"/>\n");
        sb.append("    <metadata key=\"filament_maps\" value=\"").append(repeat("1", meta.materials.size())).append("\"/>\n");
        sb.append("    <metadata key=\"limit_filament_maps\" value=\"").append(repeat("0", meta.materials.size())).append("\"/>\n");
        sb.append("    <object identify_id=\"1\" name=\"").append(escapeXml(meta.objectName))
                .append("\" skipped=\"false\" />\n");
        for (int i = 0; i < meta.materials.size(); i++) {
            sb.append("    <filament id=\"").append(i + 1)
                    .append("\" tray_info_idx=\"\" type=\"").append(escapeXml(meta.materials.get(i)))
                    .append("\" color=\"").append(escapeXml(meta.colors.get(i)))
                    .append("\" used_m=\"").append(fmt(i < meta.usedMeters.size() ? meta.usedMeters.get(i) : 0))
                    .append("\" used_g=\"").append(fmt(i < meta.usedGrams.size() ? meta.usedGrams.get(i) : 0))
                    .append("\" group_id=\"0\" nozzle_diameter=\"").append(fmt(meta.nozzle))
                    .append("\" volume_type=\"Standard\" used_for_object=\"true\" used_for_support=\"false\"/>\n");
        }
        sb.append("    <nozzle id=\"0\" extruder_id=\"1\" nozzle_diameter=\"").append(String.format(Locale.US, "%.1f", meta.nozzle))
                .append("\" volume_type=\"Standard\"/>\n");
        sb.append("    <layer_filament_lists>\n");
        sb.append("      <layer_filament_list filament_list=\"0\" layer_ranges=\"0 ")
                .append(Math.max(0, meta.layers - 1)).append("\" />\n");
        sb.append("    </layer_filament_lists>\n");
        sb.append("  </plate>\n</config>\n");
        return sb.toString();
    }



    /** Same object shape as the accepted package (a bare array is not what Orca writes). */
    private static String sequenceJson(PrintMetadata meta) {
        return "{\"plate_" + PLATE + "\":{\"nozzle_sequence\":[0],\"optimal_assignment\":[0,0,0,0,0],"
                + "\"sequence\":[" + (meta.sequence.isEmpty() ? 1 : meta.sequence.get(0) + 1) + "]}}";
    }



    // ---------------------------------------------------------------- helpers

    private static double sum(List<Double> values) {
        double total = 0;
        for (double value : values) total += value;
        return total;
    }

    private static String fmt(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private static String digest(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int len;
            while ((len = input.read(buffer)) > 0) {
                md.update(buffer, 0, len);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    private static void writeText(ZipOutputStream zip, String name, String content) throws IOException {
        writeBytes(zip, name, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeBytes(ZipOutputStream zip, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        zip.putNextEntry(entry);
        zip.write(data);
        zip.closeEntry();
    }

    private static String escapeXml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
