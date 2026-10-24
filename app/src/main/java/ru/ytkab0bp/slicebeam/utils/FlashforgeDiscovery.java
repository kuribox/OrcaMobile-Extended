package ru.ytkab0bp.slicebeam.utils;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * LAN discovery for Flashforge printers, mirroring {@code Flashforge::discover_printers()} in desktop
 * OrcaSlicer (src/slic3r/Utils/Flashforge.cpp): a 20-byte USR-WIFI232 payload is broadcast to UDP
 * 48899, replies arrive on UDP 18007 and carry the printer name (offset 0) and its serial number
 * (offset 0x92). The serial is what the HTTP API needs as {@code serialNumber}, which is why the
 * desktop picks it from this list instead of asking the user to type it.
 */
public final class FlashforgeDiscovery {

    private static final int DISCOVERY_PORT = 48899;
    private static final int LISTEN_PORT = 18007;
    private static final int MIN_RESPONSE = 0xC4;
    private static final int NAME_OFFSET = 0;
    private static final int SERIAL_OFFSET = 0x92;
    private static final int FIELD_LENGTH = 32;

    private static final byte[] MESSAGE = {
            0x77, 0x77, 0x77, 0x2e, 0x75, 0x73, 0x72, 0x22, 0x65, 0x36,
            (byte) 0xc0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    };

    public static final class Printer {
        public final String name;
        public final String serial;
        public final String ip;

        Printer(String name, String serial, String ip) {
            this.name = name;
            this.serial = serial;
            this.ip = ip;
        }

        @Override
        public String toString() {
            String label = name != null && !name.isEmpty() ? name : ip;
            if (serial != null && !serial.isEmpty()) {
                label += " — " + serial;
            }
            return label + " (" + ip + ")";
        }
    }

    private FlashforgeDiscovery() {}

    /** Blocking call: run it off the UI thread. */
    public static List<Printer> discover(int timeoutMs, int idleMs, int retries) throws IOException {
        Map<String, Printer> byIp = new LinkedHashMap<>();

        for (int attempt = 0; attempt < Math.max(1, retries); attempt++) {
            DatagramSocket socket = null;
            try {
                socket = new DatagramSocket(null);
                socket.setReuseAddress(true);
                socket.setBroadcast(true);
                socket.bind(new InetSocketAddress(LISTEN_PORT));

                for (InetAddress address : broadcastAddresses()) {
                    try {
                        socket.send(new DatagramPacket(MESSAGE, MESSAGE.length, address, DISCOVERY_PORT));
                    } catch (IOException ignored) {
                        // One unreachable broadcast network must not abort the whole sweep.
                    }
                }

                socket.setSoTimeout(50);
                long start = System.currentTimeMillis();
                long lastReply = start;
                byte[] buffer = new byte[512];

                while (true) {
                    long now = System.currentTimeMillis();
                    if (now - start >= timeoutMs) break;
                    if (!byIp.isEmpty() && now - lastReply >= idleMs) break;

                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    try {
                        socket.receive(packet);
                    } catch (SocketTimeoutException e) {
                        continue;
                    }

                    Printer printer = parse(packet.getData(), packet.getLength(),
                            packet.getAddress().getHostAddress());
                    if (printer != null) {
                        byIp.put(printer.ip, printer);
                        lastReply = System.currentTimeMillis();
                    }
                }
            } finally {
                if (socket != null) socket.close();
            }

            if (!byIp.isEmpty()) break;
        }

        List<Printer> printers = new ArrayList<>(byIp.values());
        Collections.sort(printers, (a, b) -> {
            String an = a.name == null ? "" : a.name;
            String bn = b.name == null ? "" : b.name;
            int cmp = an.compareTo(bn);
            return cmp != 0 ? cmp : a.ip.compareTo(b.ip);
        });
        return printers;
    }

    /** Parses a discovery reply; null when it is not a Flashforge response. */
    static Printer parse(byte[] data, int length, String ip) {
        if (data == null || length < MIN_RESPONSE) return null;

        String name = trimAscii(data, NAME_OFFSET, FIELD_LENGTH);
        String serial = trimAscii(data, SERIAL_OFFSET, FIELD_LENGTH);
        if (name.isEmpty() && serial.isEmpty()) return null;
        return new Printer(name, serial, ip);
    }

    private static String trimAscii(byte[] data, int offset, int length) {
        if (offset + length > data.length) return "";
        int end = offset;
        while (end < offset + length && data[end] != 0) end++;
        StringBuilder sb = new StringBuilder(end - offset);
        for (int i = offset; i < end; i++) {
            int c = data[i] & 0xFF;
            if (c >= 0x20 && c < 0x7F) sb.append((char) c);
        }
        return sb.toString().trim();
    }

    private static Set<InetAddress> broadcastAddresses() {
        Set<InetAddress> addresses = new LinkedHashSet<>();
        try {
            addresses.add(InetAddress.getByName("255.255.255.255"));
        } catch (IOException ignored) {
        }

        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress broadcast = ia.getBroadcast();
                    if (broadcast != null) addresses.add(broadcast);
                }
            }
        } catch (Exception ignored) {
        }
        return addresses;
    }
}
