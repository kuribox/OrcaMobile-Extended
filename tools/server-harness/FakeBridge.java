import ru.ytkab0bp.slicebeam.server.PlateBridge;
import ru.ytkab0bp.slicebeam.server.SliceJob;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Stand-in plate for exercising the server without Android. */
public class FakeBridge implements PlateBridge {
    final List<String> parts = new ArrayList<>();
    final java.util.Map<String,String> options = new java.util.LinkedHashMap<>();
    File lastGcode;

    @Override public String statusJson() {
        StringBuilder sb = new StringBuilder("{\"parts\":[");
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"index\":").append(i).append(",\"name\":\"").append(parts.get(i))
              .append("\",\"sizeMm\":\"10 x 10 x 10 mm\"}");
        }
        sb.append("],\"printers\":[\"AD5X\",\"Adventurer 5M\"],\"filament\":\"PLA\",\"printer\":\"AD5X\"");
        sb.append(",\"filaments\":[\"PLA\",\"PETG\"],\"bed\":\"220x220\",\"options\":{}");
        sb.append(",\"optionsFull\":").append(options).append("}");
        return sb.toString();
    }

    @Override public byte[] previewPng(int maxWidth) { return null; }

    @Override public int addPart(File file) { parts.add(file.getName()); return parts.size() - 1; }

    @Override public void removePart(int index) {
        if (index >= 0 && index < parts.size()) parts.remove(index);
    }

    @Override public void arrange() { }

    @Override public void applyOptions(Map<String, String> values) { options.putAll(values); }

    @Override public SliceJob.Stats slice(Progress progress) throws Exception {
        for (int i = 0; i <= 100; i += 20) {
            progress.onProgress(i, "fake layer " + i);
            Thread.sleep(120);
        }
        File out = File.createTempFile("fake", ".gcode");
        StringBuilder g = new StringBuilder("; total filament weight [g] = 12.34\n; estimated printing time = 1h 5m 3s\nG1 X10 Y10 E1 F1800\n");
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(g.toString().getBytes(StandardCharsets.UTF_8));
        }
        lastGcode = out;
        SliceJob.Stats stats = new SliceJob.Stats();
        stats.seconds = 3903; stats.grams = 12.34; stats.layers = 42; stats.speed = 60;
        stats.size = out.length();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest(java.nio.file.Files.readAllBytes(out.toPath()))) {
            hex.append(String.format("%02x", b));
        }
        stats.sha256 = hex.toString();
        return stats;
    }

    @Override public File gcodeFile() { return lastGcode; }

    @Override public File packageFile() throws Exception {
        File out = File.createTempFile("fake", ".gcode.3mf");
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write("PK-fake-3mf".getBytes(StandardCharsets.UTF_8));
        }
        return out;
    }

    @Override public String sendToPrinter(boolean print, boolean use3mf) {
        return "fake send print=" + print + " 3mf=" + use3mf;
    }

    @Override public boolean isReady() { return true; }
}
