package ru.ytkab0bp.slicebeam.utils;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Writes the last uncaught exception to a file so it can be read from the local server page
 * (GET /api/log) instead of needing adb logcat on a device that is not connected.
 */
public class CrashLog {

    private static final String TAG = "OrcaMCrash";
    private static final String FILE = "crash.txt";
    private static final long MAX_BYTES = 96 * 1024;

    public static void install(Context context) {
        final Context app = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                write(app, throwable);
            } catch (Throwable ignored) {
            }
            if (previous != null) previous.uncaughtException(thread, throwable);
        });
    }

    public static File file(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), FILE);
    }

    private static void write(Context context, Throwable throwable) {
        StringWriter writer = new StringWriter();
        writer.append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date()))
                .append("  thread ").append(Thread.currentThread().getName()).append('\n');
        throwable.printStackTrace(new PrintWriter(writer));
        writer.append('\n');
        byte[] bytes = writer.toString().getBytes();
        try {
            File file = file(context);
            if (file.exists() && file.length() > MAX_BYTES) {
                // Keep the newest trace only.
                file.delete();
            }
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write(bytes);
            }
        } catch (Throwable t) {
            Log.w(TAG, "could not persist crash", t);
        }
    }
}
