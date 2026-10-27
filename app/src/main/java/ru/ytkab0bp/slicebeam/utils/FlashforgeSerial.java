package ru.ytkab0bp.slicebeam.utils;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Flashforge legacy console protocol, the path desktop OrcaSlicer takes when the printer has no
 * check code configured (src/slic3r/Utils/Flashforge.cpp):
 *
 * <ul>
 *   <li>test: connect to TCP 8899 and send {@code ~M601 S1}, then wait for a reply line;</li>
 *   <li>upload: {@code ~M28 <bytes> 0:/user/<file>}, raw data chunks of 4095 bytes,
 *       {@code ~M29} to commit and {@code ~M23 0:/user/<file>} to start printing.</li>
 * </ul>
 *
 * Commands are terminated with CRLF and answered with a line; data chunks are written raw and not
 * acknowledged, which is why the console in the desktop client is a lockstep command queue.
 */
public final class FlashforgeSerial {

    private static final int PORT = 8899;
    private static final int CHUNK = 4095;
    private static final int COMMAND_TIMEOUT_MS = 8000;

    private static final String CONTROL_COMMAND = "~M601 S1";
    private static final String SAVE_COMMAND = "~M29";

    public static final class Result {
        public final boolean ok;
        public final String message;

        Result(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }
    }

    public interface Progress {
        void onProgress(int percent);
    }

    private FlashforgeSerial() {}

    /** Sends the control command; any answer line means the console is alive. */
    public static Result ping(String host) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, PORT), 5000);
            socket.setSoTimeout(COMMAND_TIMEOUT_MS);
            String reply = sendCommand(socket, CONTROL_COMMAND);
            if (reply == null) {
                return new Result(false, "El puerto " + PORT + " no respondió al comando de control.");
            }
            return new Result(true, reply.isEmpty() ? "Consola serie 8899 disponible." : reply.trim());
        } catch (IOException e) {
            return new Result(false, e.getClass().getSimpleName() + " (TCP " + PORT + "): " + e.getMessage());
        } finally {
            close(socket);
        }
    }

    /**
     * Uploads a file to {@code 0:/user/<remoteName>} over the console protocol.
     *
     * @param print start the print right after committing the file
     */
    public static Result upload(String host, File source, String remoteName, boolean print, Progress progress) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, PORT), 5000);
            socket.setSoTimeout(COMMAND_TIMEOUT_MS);

            long length = source.length();
            String reply = sendCommand(socket, "~M28 " + length + " 0:/user/" + sanitize(remoteName));
            if (reply == null) {
                return new Result(false, "La impresora no aceptó el comando de escritura (~M28).");
            }

            OutputStream out = socket.getOutputStream();
            byte[] buffer = new byte[CHUNK];
            long sent = 0;
            try (InputStream in = new BufferedInputStream(new FileInputStream(source), 64 * 1024)) {
                int len;
                while ((len = in.read(buffer)) > 0) {
                    out.write(buffer, 0, len);
                    sent += len;
                    if (progress != null) {
                        progress.onProgress((int) (sent * 100 / Math.max(1, length)));
                    }
                }
            }
            out.flush();

            // The printer needs a moment to flush its own buffer before the save command.
            sleep(3000);
            if (sendCommand(socket, SAVE_COMMAND) == null) {
                return new Result(false, "La impresora no confirmó el guardado del archivo (~M29).");
            }

            if (print) {
                if (sendCommand(socket, "~M23 0:/user/" + sanitize(remoteName)) == null) {
                    return new Result(false, "Archivo guardado, pero la impresora no confirmó el inicio de impresión (~M23).");
                }
                return new Result(true, "Archivo enviado e impresión iniciada.");
            }
            return new Result(true, "Archivo enviado a la impresora.");
        } catch (IOException e) {
            return new Result(false, e.getClass().getSimpleName() + " (TCP " + PORT + "): " + e.getMessage());
        } finally {
            close(socket);
        }
    }

    /** Writes a command terminated with CRLF and returns the first reply line, or null on timeout. */
    private static String sendCommand(Socket socket, String command) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write((command + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();

        InputStream in = socket.getInputStream();
        StringBuilder sb = new StringBuilder();
        try {
            while (true) {
                int c = in.read();
                if (c < 0) break;
                if (c == '\n') break;
                if (c != '\r') sb.append((char) c);
                if (sb.length() > 4096) break;
            }
        } catch (java.net.SocketTimeoutException e) {
            return null;
        }
        return sb.toString();
    }

    /** Mirrors sanitize_flashforge_filename(): only [A-Za-z0-9._-] survives. */
    private static String sanitize(String name) {
        String base = name == null ? "print.gcode" : name;
        StringBuilder sb = new StringBuilder(base.length());
        for (char c : base.toCharArray()) {
            boolean ok = (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || c == '.' || c == '_' || c == '-';
            sb.append(ok ? c : '_');
        }
        return sb.toString();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void close(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
