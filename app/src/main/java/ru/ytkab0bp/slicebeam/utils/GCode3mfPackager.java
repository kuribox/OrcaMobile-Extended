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
import java.util.Enumeration;
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
    private static final String MODEL_RELS = "3D/_rels/3dmodel.model.rels";

    private static final int PLATE = 1;
    private static final int THUMBNAIL_SIZE = 300;
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

    private static final class ObjectInfo {
        final String id;
        final String name;

        ObjectInfo(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private static final class PrintMetadata {
        final Map<String, String> settings = new LinkedHashMap<>();
        int layers;
        int seconds;
        float nozzle = 0.4f;
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
                                        GCodeProcessorResult gcodeResult,
                                        File model3mf) throws Exception {
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

        Map<String, byte[]> base = new LinkedHashMap<>();
        if (model3mf != null && model3mf.isFile()) {
            base.putAll(readZip(model3mf));
        }
        byte[] modelXml = base.get(MODEL_FILE);
        List<ObjectInfo> objects = parseObjects(modelXml);

        File parent = destination.getParentFile();
        if (parent != null) parent.mkdirs();
        if (destination.exists() && !destination.delete()) {
            throw new IOException("Could not replace the previous .gcode.3mf file.");
        }

        String gcodePath = "Metadata/plate_" + PLATE + ".gcode";

        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(destination))) {
            writeText(zip, CONTENT_TYPES, contentTypesXml());
            writeText(zip, ROOT_RELS, rootRelationshipsXml());

            if (modelXml != null) {
                writeBytes(zip, MODEL_FILE, modelXml);
            } else {
                writeText(zip, MODEL_FILE, minimalModelXml());
            }
            if (base.containsKey(MODEL_RELS)) {
                writeBytes(zip, MODEL_RELS, base.get(MODEL_RELS));
            }
            // Remaining parts of the model export (cut info, object config, textures…).
            for (Map.Entry<String, byte[]> entry : base.entrySet()) {
                String name = entry.getKey();
                if (name.equals(CONTENT_TYPES) || name.equals(ROOT_RELS)
                        || name.equals(MODEL_FILE) || name.equals(MODEL_RELS)
                        || name.startsWith("Metadata/plate_")) {
                    continue;
                }
                writeBytes(zip, name, entry.getValue());
            }

            writeBytes(zip, "Metadata/plate_" + PLATE + ".png", platePng);
            writeBytes(zip, "Metadata/plate_" + PLATE + "_small.png", smallPng);
            writeText(zip, "Metadata/plate_" + PLATE + ".json", plateJson(objects));
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

            writeText(zip, "Metadata/model_settings.config", modelSettingsXml(objects));
            writeText(zip, "Metadata/_rels/model_settings.config.rels",
                    "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">\n"
                            + "  <Relationship Target=\"/" + gcodePath + "\" Id=\"rel-1\" "
                            + "Type=\"http://schemas.bambulab.com/package/2021/gcode\"/>\n"
                            + "</Relationships>\n");
            writeText(zip, "Metadata/cut_information.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<objects/>\n");
            writeText(zip, "Metadata/slice_info.config", sliceInfoXml(meta, objects, printer));
            writeText(zip, "Metadata/filament_sequence.json", sequenceJson(meta));
        } catch (Exception e) {
            destination.delete();
            throw e;
        }

        return new PackageSummary(meta.layers, meta.seconds, meta.materials.size(), platePng.length,
                objects.size(), modelXml != null);
    }

    /** Checks the parts a strict importer looks for. */
    public static boolean verify(File packageFile) {
        try (ZipFile zip = new ZipFile(packageFile)) {
            String[] required = {
                    CONTENT_TYPES, ROOT_RELS, MODEL_FILE,
                    "Metadata/plate_" + PLATE + ".png",
                    "Metadata/plate_" + PLATE + "_small.png",
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

    private static PrintMetadata readMetadata(File gcodeFile) throws IOException {
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

    private static Map<String, byte[]> readZip(File file) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(file)) {
            Enumeration<? extends ZipEntry> enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                if (entry.isDirectory()) continue;
                try (InputStream input = zip.getInputStream(entry)) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[64 * 1024];
                    int len;
                    while ((len = input.read(buffer)) > 0) {
                        out.write(buffer, 0, len);
                    }
                    entries.put(entry.getName(), out.toByteArray());
                }
            }
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
        return entries;
    }

    private static List<ObjectInfo> parseObjects(byte[] modelXml) {
        List<ObjectInfo> objects = new ArrayList<>();
        if (modelXml == null) return objects;
        String xml = new String(modelXml, StandardCharsets.UTF_8);
        Matcher object = MODEL_OBJECT.matcher(xml);
        while (object.find()) {
            String id = object.group(1);
            int start = object.end();
            int end = xml.indexOf("</object>", start);
            String body = end > start ? xml.substring(start, end) : "";
            String name = null;
            Matcher meta = META_NAME.matcher(body);
            if (meta.find()) {
                name = meta.group(1);
            } else {
                Matcher attr = OBJECT_ATTR_NAME.matcher(object.group());
                if (attr.find()) name = attr.group(1);
            }
            objects.add(new ObjectInfo(id, name == null || name.isEmpty() ? "Object " + id : unescapeXml(name)));
        }
        return objects;
    }

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

    private static String minimalModelXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<model unit=\"millimeter\" xml:lang=\"en-US\" xmlns=\"http://schemas.microsoft.com/3dmanufacturing/2013/01\">\n"
                + " <metadata name=\"Application\">OrcaSlicer Mobile</metadata>\n"
                + " <metadata name=\"ModifiedDate\">"
                + new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(new Date()) + "</metadata>\n"
                + " <resources/>\n"
                + " <build/>\n"
                + "</model>\n";
    }

    private static String plateJson(List<ObjectInfo> objects) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"plate_index\": ").append(PLATE).append(",\n  \"bbox_objects\": [");
        for (int i = 0; i < objects.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("{\"id\": ").append(objects.get(i).id)
                    .append(", \"name\": \"").append(escapeJson(objects.get(i).name)).append("\"}");
        }
        sb.append("],\n  \"bed_type\": \"textured_plate\"\n}\n");
        return sb.toString();
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

    private static String modelSettingsXml(List<ObjectInfo> objects) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<config>\n");
        for (ObjectInfo object : objects) {
            sb.append("  <object id=\"").append(object.id).append("\">\n");
            sb.append("    <metadata key=\"name\" value=\"").append(escapeXml(object.name)).append("\"/>\n");
            sb.append("    <metadata key=\"extruder\" value=\"1\"/>\n");
            sb.append("  </object>\n");
        }
        sb.append("  <plate>\n");
        sb.append("    <metadata key=\"plater_id\" value=\"").append(PLATE).append("\"/>\n");
        sb.append("    <metadata key=\"plater_name\" value=\"\"/>\n");
        sb.append("    <metadata key=\"locked\" value=\"false\"/>\n");
        sb.append("    <metadata key=\"thumbnail_file\" value=\"Metadata/plate_").append(PLATE).append(".png\"/>\n");
        for (ObjectInfo object : objects) {
            sb.append("    <object identify_id=\"").append(object.id).append("\" name=\"")
                    .append(escapeXml(object.name)).append("\"/>\n");
        }
        sb.append("  </plate>\n</config>\n");
        return sb.toString();
    }

    private static String sliceInfoXml(PrintMetadata meta, List<ObjectInfo> objects, ConfigObject printer) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<slice_info>\n");
        sb.append("  <header>\n");
        sb.append("    <header_item key=\"X-BBL-Client-Type\" value=\"slicer\"/>\n");
        sb.append("    <header_item key=\"X-BBL-Client-Version\" value=\"OrcaSlicer Mobile\"/>\n");
        sb.append("  </header>\n");
        sb.append("  <plate>\n");
        sb.append("    <metadata key=\"index\" value=\"").append(PLATE).append("\"/>\n");
        if (printer != null) {
            sb.append("    <metadata key=\"printer_model_id\" value=\"")
                    .append(escapeXml(printer.getTitle())).append("\"/>\n");
        }
        sb.append("    <metadata key=\"nozzle_diameters\" value=\"")
                .append(String.format(Locale.US, "%.1f", meta.nozzle)).append("\"/>\n");
        sb.append("    <metadata key=\"prediction\" value=\"").append(meta.seconds).append("\"/>\n");
        sb.append("    <metadata key=\"weight\" value=\"").append(fmt(sum(meta.usedGrams))).append("\"/>\n");
        for (int i = 0; i < meta.materials.size(); i++) {
            sb.append("    <filament id=\"").append(i + 1).append("\" tray_info_idx=\"\" type=\"")
                    .append(escapeXml(meta.materials.get(i))).append("\" color=\"")
                    .append(escapeXml(meta.colors.get(i))).append("\" used_m=\"")
                    .append(fmt(i < meta.usedMeters.size() ? meta.usedMeters.get(i) : 0)).append("\" used_g=\"")
                    .append(fmt(i < meta.usedGrams.size() ? meta.usedGrams.get(i) : 0)).append("\"/>\n");
        }
        for (ObjectInfo object : objects) {
            sb.append("    <object identify_id=\"").append(object.id).append("\" name=\"")
                    .append(escapeXml(object.name)).append("\" skipped=\"false\"/>\n");
        }
        sb.append("  </plate>\n</slice_info>\n");
        return sb.toString();
    }

    private static String sequenceJson(PrintMetadata meta) {
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < meta.sequence.size(); i++) {
            if (i > 0) sb.append(",\n");
            sb.append("  ").append(meta.sequence.get(i));
        }
        sb.append("\n]\n");
        return sb.toString();
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

    /** Object names are read from the model XML, so entities must be decoded before re-escaping. */
    private static String unescapeXml(String value) {
        if (value == null) return null;
        return value.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&");
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
