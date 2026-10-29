package ru.ytkab0bp.slicebeam.server;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import ru.ytkab0bp.slicebeam.R;
import ru.ytkab0bp.slicebeam.SliceBeam;
import ru.ytkab0bp.slicebeam.fragment.BedFragment;
import ru.ytkab0bp.slicebeam.utils.Prefs;

/**
 * Keeps the local HTTP server alive while the app is open.
 *
 * <p>A foreground service is required for two practical reasons: Android stops sockets of frozen
 * background processes, and the Wi-Fi radio sleeps unless something holds a lock.
 */
public class LocalServerService extends Service {

    public static final String ACTION_START = "ru.ytkab0bp.slicebeam.LOCAL_SERVER_START";
    public static final String ACTION_STOP = "ru.ytkab0bp.slicebeam.LOCAL_SERVER_STOP";

    private static final String CHANNEL = "orcam_local_server";
    private static final int NOTIFICATION_ID = 0x0CA1;
    private static final String KEY_TOKEN = "local_server_token";
    private static final long IDLE_LIMIT_MS = 30L * 60L * 1000L;

    private static WeakReference<BedFragment> bedRef = new WeakReference<>(null);
    private static volatile boolean running;

    private LocalHttpServer server;
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable idleCheck = new Runnable() {
        @Override
        public void run() {
            if (server != null && server.getIdleMillis() > IDLE_LIMIT_MS) {
                stopSelf();
                return;
            }
            handler.postDelayed(this, 60_000L);
        }
    };

    public static void attach(BedFragment fragment) {
        bedRef = new WeakReference<>(fragment);
    }

    public static void detach(BedFragment fragment) {
        if (bedRef.get() == fragment) bedRef = new WeakReference<>(null);
    }

    public static boolean isRunning() {
        return running;
    }

    public static void start(Context context) {
        Intent intent = new Intent(context, LocalServerService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        // stopService always works; starting a service to make it stop is restricted in background.
        context.stopService(new Intent(context, LocalServerService.class));
    }

    /** Token shown in the settings screen; generated once per install. */
    public static String token(Context context) {
        String stored = Prefs.getPrefs().getString(KEY_TOKEN, null);
        if (stored != null && stored.length() >= 8) return stored;
        return rotateToken(context);
    }

    public static String rotateToken(Context context) {
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Prefs.getPrefs().edit().putString(KEY_TOKEN, token).apply();
        return token;
    }

    public static String localAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface network : interfaces) {
                if (!network.isUp() || network.isLoopback()) continue;
                String name = network.getName();
                if (name != null && (name.startsWith("rmnet") || name.startsWith("dummy"))) continue;
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static String url(Context context) {
        String host = localAddress();
        if (host == null) host = "127.0.0.1";
        return String.format(Locale.ROOT, "http://%s:%d/?t=%s",
                host, LocalHttpServer.DEFAULT_PORT, token(context));
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (server != null) {
            notifyForeground();
            return START_NOT_STICKY;
        }
        startForegroundSafely();

        BedFragment bed = bedRef.get();
        if (bed == null) {
            notifyMessage(getString(R.string.LocalServerNeedsApp));
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            File web = new File(getFilesDir(), "web");
            copyWebAssets(web);
            File uploads = new File(getCacheDir(), "server");
            server = new LocalHttpServer(LocalHttpServer.DEFAULT_PORT,
                    new BedPlateBridge(bed), web, uploads, token(this));
            running = true;
            keepAwake(true);
            handler.postDelayed(idleCheck, 60_000L);
            notifyForeground();
        } catch (Throwable t) {
            notifyMessage(getString(R.string.LocalServerFailed, String.valueOf(t.getMessage())));
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (server != null) {
            server.shutdown();
            server = null;
        }
        keepAwake(false);
        super.onDestroy();
    }

    // ------------------------------------------------------------------ helpers

    private void keepAwake(boolean enable) {
        try {
            if (enable && wifiLock == null) {
                WifiManager wifi = (WifiManager) getApplicationContext()
                        .getSystemService(Context.WIFI_SERVICE);
                if (wifi != null) {
                    wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "orcam:local-server");
                    wifiLock.acquire();
                }
            }
            if (enable && wakeLock == null) {
                PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (power != null) {
                    wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "orcam:local-server");
                    wakeLock.acquire(60L * 60L * 1000L);
                }
            }
        } catch (Throwable ignored) {
        }
        if (!enable) {
            try {
                if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
            } catch (Throwable ignored) {
            }
            try {
                if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            } catch (Throwable ignored) {
            }
            wifiLock = null;
            wakeLock = null;
        }
    }

    private void startForegroundSafely() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null && manager.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL,
                        getString(R.string.LocalServerChannel), NotificationManager.IMPORTANCE_LOW);
                channel.setShowBadge(false);
                manager.createNotificationChannel(channel);
            }
        }
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.LocalServerStarting)));
    }

    private Notification buildNotification(String text) {
        Intent stop = new Intent(this, LocalServerService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.printer_outline_28)
                .setContentTitle(getString(R.string.LocalServerTitle))
                .setContentText(text)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(0, getString(R.string.LocalServerStop), stopIntent)
                .build();
    }

    private void notifyForeground() {
        String host = localAddress();
        String text = host == null
                ? getString(R.string.LocalServerNoWifi)
                : String.format(Locale.ROOT, "http://%s:%d", host, LocalHttpServer.DEFAULT_PORT);
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(text));
    }

    private void notifyMessage(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID + 1, new NotificationCompat.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.printer_outline_28)
                    .setContentTitle(getString(R.string.LocalServerTitle))
                    .setContentText(text)
                    .setAutoCancel(true)
                    .build());
        }
    }

    /** Copies assets/web into the files dir: NanoHTTPD serves plain files, not APK assets. */
    private void copyWebAssets(File destination) throws Exception {
        String[] names = getAssets().list("web");
        if (names == null || names.length == 0) throw new IllegalStateException("web assets missing");
        if (!destination.exists() && !destination.mkdirs()) throw new IllegalStateException("no web dir");
        for (String name : names) {
            File target = new File(destination, name);
            try (InputStream in = getAssets().open("web/" + name);
                 OutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            }
        }
    }
}
