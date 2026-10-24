package ru.ytkab0bp.slicebeam.utils;

import ru.ytkab0bp.slicebeam.config.ConfigObject;
import ru.ytkab0bp.slicebeam.slic3r.GCodeProcessorResult;
import ru.ytkab0bp.slicebeam.slic3r.GCodeViewer;

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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds a generic .gcode.3mf package (OPC/3MF zip) containing G-code and metadata.
 * Compatible with printers that accept .gcode.3mf format (Flashforge, Bambu, Qidi, etc.).
 */
public class GCode3mfPackager {

    private static final int MAX_THUMBNAIL_BYTES = 4 * 1024 * 1024;
    private static final int PLATE = 1;

    // Regex patterns for parsing G-code metadata
    private static final java.util.regex.Pattern SETTING_LINE = java.util.regex.Pattern.compile("^;\\s*([^=]+?)\\s*=\\s*(.*?)\\s*$");
    private static final java.util.regex.Pattern LAYER_LINE = java.util.regex.Pattern.compile("(?i)^;\\s*total layer number\\s*:\\s*(\\d+)\\s*$");
    private static final java.util.regex.Pattern TOOL_LINE = java.util.regex.Pattern.compile("(?i)^T(\\d+)(?:\\s|$)");
    private static final java.util.regex.Pattern THUMBNAIL_BEGIN = java.util.regex.Pattern.compile("(?i)^;\\s*thumbnail\\s+begin\\s+(\\d+)x(\\d+)\\s+(\\d+)(?:\\s+.*)?$");
    private static final java.util.regex.Pattern DURATION_PART = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*([dhms])", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern REMAINING_TIME_LINE = java.util.regex.Pattern.compile("(?i)^M73\\s+P(\\d+)\\s+R(\\d+)");

    public static class PackageSummary {
        public final int layers;
        public final int durationSeconds;
        public final int filamentCount;
        public final int thumbnailBytes;

        public PackageSummary(int layers, int durationSeconds, int filamentCount, int thumbnailBytes) {
            this.layers = layers;
            this.durationSeconds = durationSeconds;
            this.filamentCount = filamentCount;
            this.thumbnailBytes = thumbnailBytes;
        }
    }

    private static class PrintMetadata {
        Map<String, String> settings = new HashMap<>();
        int layers = 0;
        int seconds = 0;
        double firstLayerSeconds = 0;
        double nozzle = 0.4;
        List<String> materials = new ArrayList<>();
        List<String> colors = new ArrayList<>();
        List<Double> usedMeters = new ArrayList<>();
        List<Double> usedGrams = new ArrayList<>();
        List<String> filamentIds = new ArrayList<>();
        List<String> filamentMaps = new ArrayList<>();
        List<Integer> sequence = new ArrayList<>();
    }

    /**
     * Creates a .gcode.3mf package from a sliced G-code file.
     *
     * @param sourceGcode     The sliced G-code file
     * @param destination     Output .gcode.3mf file
     * @param previewPng      PNG thumbnail (can be null, will try to extract from G-code)
     * @param printer         Printer config object
     * @param gcodeResult     Slice result for metadata
     * @return Package summary
     * @throws Exception on failure
     */
    public static PackageSummary create(
            File sourceGcode,
            File destination,
            byte[] previewPng,
            ConfigObject printer,
            GCodeProcessorResult gcodeResult
    ) throws Exception {
        if (sourceGcode == null || !sourceGcode.isFile() || sourceGcode.length() == 0L) {
            throw new IllegalArgumentException("The sliced G-code file is missing or empty.");
        }

        PrintMetadata metadata = readMetadata(sourceGcode, gcodeResult);
        if (metadata.layers <= 0) {
            throw new IllegalArgumentException("The sliced file does not declare a valid layer count.");
        }

        byte[] thumbnail = previewPng;
        if (thumbnail == null || thumbnail.length == 0) {
            thumbnail = readGcodeThumbnail(sourceGcode);
        }
        if (thumbnail == null || thumbnail.length == 0) {
            // Create a minimal placeholder thumbnail (1x1 transparent PNG)
            thumbnail = createPlaceholderThumbnail();
        }
        if (thumbnail.length > MAX_THUMBNAIL_BYTES) {
            // Truncate if too large (shouldn't happen with placeholder)
            thumbnail = java.util.Arrays.copyOf(thumbnail, MAX_THUMBNAIL_BYTES);
        }

        destination.getParentFile().mkdirs();
        if (destination.exists() && !destination.delete()) {
            throw new IOException("Could not replace the previous .gcode.3mf file.");
        }

        String packageGcodePath = "Metadata/plate_" + PLATE + ".gcode";
        String md5 = digest(sourceGcode);

        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(destination))) {
            writeText(zip, "[Content_Types].xml", contentTypesXml());
            writeText(zip, "_rels/.rels", rootRelationshipsXml());
            writeText(zip, "3D/3dmodel.model", modelXml());
            writeBytes(zip, "Metadata/plate_" + PLATE + ".png", thumbnail);
            writeBytes(zip, "Metadata/plate_no_light_" + PLATE + ".png", thumbnail);
            writeBytes(zip, "Metadata/top_" + PLATE + ".png", thumbnail);
            writeBytes(zip, "Metadata/pick_" + PLATE + ".png", thumbnail);
            writeText(zip, "Metadata/plate_" + PLATE + ".json", plateJson(metadata));
            writeText(zip, "Metadata/project_settings.config", settingsJson(metadata, printer));
            writeText(zip, "Metadata/plate_" + PLATE + ".gcode.md5", md5);
            zip.putNextEntry(new ZipEntry(packageGcodePath));
            try (FileInputStream input = new FileInputStream(sourceGcode)) {
                byte[] buffer = new byte[128 * 1024];
                int len;
                while ((len = input.read(buffer)) > 0) {
                    zip.write(buffer, 0, len);
                }
            }
            zip.closeEntry();
            writeText(zip, "Metadata/_rels/model_settings.config.rels", modelSettingsRelationshipsXml(packageGcodePath));
            writeText(zip, "Metadata/model_settings.config", modelSettingsXml(metadata));
            writeText(zip, "Metadata/cut_information.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<objects/>\n");
            writeText(zip, "Metadata/slice_info.config", sliceInfoXml(metadata, printer));
            writeText(zip, "Metadata/filament_sequence.json", sequenceJson(metadata));
        } catch (Exception e) {
            destination.delete();
            throw e;
        }

        return new PackageSummary(metadata.layers, metadata.seconds, metadata.materials.size(), thumbnail.length);
    }

    /**
     * Verifies a .gcode.3mf package integrity.
     */
    public static boolean verify(File packageFile) {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(packageFile)) {
            String[] requiredEntries = {
                "[Content_Types].xml", "_rels/.rels", "3D/3dmodel.model",
                "Metadata/_rels/model_settings.config.rels", "Metadata/cut_information.xml",
                "Metadata/filament_sequence.json", "Metadata/model_settings.config",
                "Metadata/pick_" + PLATE + ".png", "Metadata/plate_" + PLATE + ".gcode",
                "Metadata/plate_" + PLATE + ".gcode.md5", "Metadata/plate_" + PLATE + ".json",
                "Metadata/plate_" + PLATE + ".png", "Metadata/plate_no_light_" + PLATE + ".png",
                "Metadata/project_settings.config", "Metadata/slice_info.config",
                "Metadata/top_" + PLATE + ".png"
            };
            for (String entry : requiredEntries) {
                if (zip.getEntry(entry) == null) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static PrintMetadata readMetadata(File gcodeFile, GCodeProcessorResult gcodeResult) throws IOException {
        PrintMetadata meta = new PrintMetadata();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(gcodeFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // Parse settings lines: ; key = value
                java.util.regex.Matcher m = SETTING_LINE.matcher(line);
                if (m.matches()) {
                    meta.settings.put(m.group(1).trim(), m.group(2).trim());
                    continue;
                }
                // Parse layer count
                m = LAYER_LINE.matcher(line);
                if (m.matches()) {
                    meta.layers = Integer.parseInt(m.group(1));
                    continue;
                }
                // Parse tool changes for filament sequence
                m = TOOL_LINE.matcher(line);
                if (m.matches()) {
                    int tool = Integer.parseInt(m.group(1));
                    if (!meta.sequence.contains(tool)) {
                        meta.sequence.add(tool);
                    }
                }
            }
        }

        // Extract metadata from GCodeProcessorResult if available
        if (gcodeResult != null) {
            // GCodeProcessorResult doesn't expose estimated time or filament count/type/color directly.
            // Use sequence from tool changes as filament count.
            int filamentCount = meta.sequence.isEmpty() ? 1 : meta.sequence.size();
            for (int i = 0; i < filamentCount; i++) {
                meta.materials.add("PLA"); // Default, could be parsed from settings
                meta.colors.add("#FFFFFF"); // Default white
                meta.usedMeters.add(0.0);
                meta.usedGrams.add(0.0);
                meta.filamentIds.add("");
                meta.filamentMaps.add("");
            }
            // If we have usage data per role, distribute it
            if (!meta.sequence.isEmpty()) {
                for (int i = 0; i < Math.min(meta.sequence.size(), GCodeViewer.EXTRUSION_ROLES_COUNT); i++) {
                    if (i < meta.usedMeters.size()) {
                        meta.usedMeters.set(i, gcodeResult.getUsedFilamentMM(i) / 1000.0);
                        meta.usedGrams.set(i, gcodeResult.getUsedFilamentG(i));
                    }
                }
            }
        }

        // Default nozzle diameter
        String nozzleStr = meta.settings.get("nozzle_diameter");
        if (nozzleStr != null) {
            try { meta.nozzle = Double.parseDouble(nozzleStr); } catch (NumberFormatException ignored) {}
        }

        // Try to parse estimated time from settings
        String timeStr = meta.settings.get("total_time");
        if (timeStr != null) {
            try { meta.seconds = Integer.parseInt(timeStr); } catch (NumberFormatException ignored) {}
        }

        return meta;
    }

    private static byte[] readGcodeThumbnail(File gcodeFile) {
        // OrcaSlicer/PrusaSlicer embed previews as:
        //   ; thumbnail begin 300x300 34506
        //   ; iVBORw0KGgo...
        //   ; thumbnail end
        // Pick the largest embedded PNG so the package carries a real preview.
        byte[] best = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(gcodeFile), StandardCharsets.ISO_8859_1))) {
            String line;
            int width = 0, height = 0, declaredSize = 0;
            StringBuilder payload = null;
            while ((line = reader.readLine()) != null) {
                java.util.regex.Matcher m = THUMBNAIL_BEGIN.matcher(line);
                if (m.matches()) {
                    width = Integer.parseInt(m.group(1));
                    height = Integer.parseInt(m.group(2));
                    declaredSize = Integer.parseInt(m.group(3));
                    payload = new StringBuilder();
                    continue;
                }
                if (payload != null) {
                    String trimmed = line.trim();
                    if (trimmed.regionMatches(true, 0, "; thumbnail end", 0, 15)) {
                        byte[] decoded = decodeBase64(payload.toString());
                        payload = null;
                        if (decoded != null && isPng(decoded) && decoded.length <= MAX_THUMBNAIL_BYTES) {
                            if (best == null || decoded.length > best.length) {
                                best = decoded;
                            }
                        }
                        continue;
                    }
                    int idx = trimmed.indexOf(';');
                    if (idx == 0) {
                        payload.append(trimmed.substring(1).trim());
                    }
                    if (payload.length() > MAX_THUMBNAIL_BYTES * 2) {
                        // Broken or oversized block: drop it and keep scanning.
                        payload = null;
                    }
                }
            }
        } catch (IOException ignored) {}
        return best;
    }

    private static byte[] decodeBase64(String data) {
        try {
            return android.util.Base64.decode(data, android.util.Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isPng(byte[] bytes) {
        return bytes != null && bytes.length > 8
                && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47;
    }

    private static byte[] createPlaceholderThumbnail() {
        // Minimal 1x1 transparent PNG
        return new byte[]{
            (byte) 0x89, (byte) 0x50, (byte) 0x4E, (byte) 0x47, (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A, // PNG signature
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x0D, (byte) 0x49, (byte) 0x48, (byte) 0x44, (byte) 0x52,       // IHDR chunk
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x01,
            (byte) 0x08, (byte) 0x06, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x1F, (byte) 0x15, (byte) 0xC4, (byte) 0x89,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x0A, (byte) 0x49, (byte) 0x44, (byte) 0x41, (byte) 0x54,       // IDAT chunk
            (byte) 0x78, (byte) 0x9C, (byte) 0x63, (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x05, (byte) 0x00, (byte) 0x01,
            (byte) 0x0D, (byte) 0x0A, (byte) 0x2D, (byte) 0xB4, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,       // IEND chunk
            (byte) 0x49, (byte) 0x45, (byte) 0x4E, (byte) 0x44, (byte) 0xAE, (byte) 0x42, (byte) 0x60, (byte) 0x82
        };
    }

    private static String digest(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = fis.read(buffer)) > 0) {
                md.update(buffer, 0, len);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    private static String digest(InputStream input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] buffer = new byte[8192];
        int len;
        while ((len = input.read(buffer)) > 0) {
            md.update(buffer, 0, len);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    private static void writeText(ZipOutputStream zip, String name, String content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        zip.putNextEntry(entry);
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void writeBytes(ZipOutputStream zip, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        zip.putNextEntry(entry);
        zip.write(data);
        zip.closeEntry();
    }

    private static String contentTypesXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">\n" +
                "  <Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>\n" +
                "  <Default Extension=\"model\" ContentType=\"application/vnd.ms-package.3dmanufacturing-3dmodel+xml\"/>\n" +
                "  <Default Extension=\"json\" ContentType=\"application/json\"/>\n" +
                "  <Default Extension=\"png\" ContentType=\"image/png\"/>\n" +
                "  <Default Extension=\"config\" ContentType=\"application/vnd.ms-package.3dmanufacturing-3dmodel+xml\"/>\n" +
                "  <Default Extension=\"xml\" ContentType=\"application/xml\"/>\n" +
                "  <Default Extension=\"md5\" ContentType=\"text/plain\"/>\n" +
                "  <Default Extension=\"gcode\" ContentType=\"text/plain\"/>\n" +
                "</Types>\n";
    }

    private static String rootRelationshipsXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">\n" +
                "  <Relationship Id=\"rId1\" Type=\"http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel\" Target=\"/3D/3dmodel.model\"/>\n" +
                "  <Relationship Id=\"rId2\" Type=\"http://schemas.microsoft.com/3dmanufacturing/2013/01/slice\" Target=\"/Metadata/slice_info.config\"/>\n" +
                "</Relationships>\n";
    }

    private static String modelXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<model unit=\"millimeter\" xml:lang=\"en-US\" xmlns=\"http://schemas.microsoft.com/3dmanufacturing/2013/01\">\n" +
                "  <metadata name=\"Application\">OrcaSlicer Mobile</metadata>\n" +
                "  <metadata name=\"ModifiedDate\">" + new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(new Date()) + "</metadata>\n" +
                "  <resources/>\n" +
                "  <build/>\n" +
                "</model>\n";
    }

    private static String plateJson(PrintMetadata meta) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"plate\": ").append(PLATE).append(",\n");
        sb.append("  \"height\": 0,\n");
        sb.append("  \"objects\": [],\n");
        sb.append("  \"settings\": {}\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static String settingsJson(PrintMetadata meta, ConfigObject printer) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"print\": {}\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static String modelSettingsXml(PrintMetadata meta) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<config>\n" +
                "  <metadata/>\n" +
                "  <settings/>\n" +
                "</config>\n";
    }

    private static String modelSettingsRelationshipsXml(String gcodePath) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">\n" +
                "  <Relationship Id=\"rId1\" Type=\"http://schemas.microsoft.com/3dmanufacturing/2013/01/gcode\" Target=\"" + gcodePath + "\"/>\n" +
                "</Relationships>\n";
    }

    private static String sliceInfoXml(PrintMetadata meta, ConfigObject printer) {
        String printerName = printer != null ? printer.getTitle() : "Unknown Printer";
        double usedMeters = meta.usedMeters.stream().mapToDouble(d -> d).sum();
        double usedGrams = meta.usedGrams.stream().mapToDouble(d -> d).sum();
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<slice_info>\n");
        sb.append("  <header>\n");
        sb.append("    <header_item key=\"X-BBL-Client-Type\" value=\"slicer\"/>\n");
        sb.append("    <header_item key=\"X-BBL-Client-Version\" value=\"OrcaSlicer Mobile\"/>\n");
        sb.append("  </header>\n");
        sb.append("  <plate>\n");
        sb.append("    <metadata key=\"index\" value=\"").append(PLATE).append("\"/>\n");
        sb.append("    <metadata key=\"printer_model_id\" value=\"").append(escapeXml(printerName)).append("\"/>\n");
        sb.append("    <metadata key=\"prediction\" value=\"").append(meta.seconds).append("\"/>\n");
        sb.append("    <metadata key=\"weight\" value=\"").append(fmt(usedGrams)).append("\"/>\n");
        sb.append("    <metadata key=\"outside\" value=\"false\"/>\n");
        for (int i = 0; i < meta.materials.size(); i++) {
            sb.append("    <filament id=\"").append(i + 1).append("\"")
                    .append(" tray_info_idx=\"\"")
                    .append(" type=\"").append(escapeXml(meta.materials.get(i))).append("\"")
                    .append(" color=\"").append(escapeXml(meta.colors.get(i))).append("\"")
                    .append(" used_m=\"").append(fmt(i < meta.usedMeters.size() ? meta.usedMeters.get(i) : 0)).append("\"")
                    .append(" used_g=\"").append(fmt(i < meta.usedGrams.size() ? meta.usedGrams.get(i) : 0)).append("\"")
                    .append("/>\n");
        }
        sb.append("  </plate>\n");
        sb.append("</slice_info>\n");
        return sb.toString();
    }

    private static String fmt(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private static String sequenceJson(PrintMetadata meta) {
        StringBuilder sb = new StringBuilder();
        sb.append("[\n");
        for (int i = 0; i < meta.sequence.size(); i++) {
            if (i > 0) sb.append(",\n");
            sb.append("  ").append(meta.sequence.get(i));
        }
        sb.append("\n]\n");
        return sb.toString();
    }

    private static String escapeXml(String s) {
            return s.replace("&", "&")
                    .replace("<", "<")
                    .replace(">", ">")
                    .replace("\"", "\"")
                    .replace("'", "&apos;");
        }
    }