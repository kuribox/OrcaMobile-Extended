package ru.ytkab0bp.slicebeam.server;

import java.io.File;
import java.util.Map;

/**
 * Everything the local HTTP server needs from the app, with no Android dependency on purpose: the
 * server can then be exercised from a plain JVM harness (tools/server-harness) instead of only on a
 * device.
 */
public interface PlateBridge {

    interface Progress {
        void onProgress(int percent, String text);
    }

    /** JSON with the plate parts, the active presets and the last slice summary. */
    String statusJson() throws Exception;

    /** PNG of the plate, or the last slice thumbnail. May return null when neither is available. */
    byte[] previewPng(int maxWidth) throws Exception;

    /** Loads a model file onto the plate and arranges the parts; returns the new part index. */
    int addPart(File file) throws Exception;

    void removePart(int index) throws Exception;

    void arrange() throws Exception;

    /** Applies profile and print options. Unknown keys are ignored. */
    void applyOptions(Map<String, String> options) throws Exception;

    /** Slices the plate. Blocking; runs on the server's job thread. */
    SliceJob.Stats slice(Progress progress) throws Exception;

    File gcodeFile() throws Exception;

    /** Builds (or reuses) the .gcode.3mf package of the last slice. */
    File packageFile() throws Exception;

    /** Uploads the last slice to the printer profile. Blocking. */
    String sendToPrinter(boolean print, boolean use3mf) throws Exception;

    /** False when the app screen is gone and the model cannot be touched. */
    boolean isReady();

    /** Empty when ready; otherwise a short reason ("app_closed", "no_model", …). */
    default String notReadyReason() {
        return isReady() ? "" : "not_ready";
    }
}
