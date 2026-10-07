package com.elthon.timewarp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

/**
 * Foreground keep-alive while a warp is active. Holds the process alive so the
 * warp SNTP server keeps answering and the system's networking continues to
 * believe the warped clock. Every few minutes it re-asserts the NTP wiring and
 * self-heals the clock if something (auto-time, user, another app) yanked it.
 */
public class WarpGuardService extends Service {

    private static final String TAG = "TimeWarp";
    private static final String CHANNEL = "timewarp_guard";
    private static final int NOTIF_ID = 8;
    private static final long TICK_MS = 5L * 60L * 1000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private TimeEngine engine;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                if (Prefs.getLastTarget(WarpGuardService.this) <= 0) {
                    stopSelf();
                    return;
                }
                if (Prefs.getOffset(WarpGuardService.this) != 0) {
                    if (!SntpServer.running()) {
                        Log.i(TAG, "guard: warp SNTP server down, re-wiring NTP");
                        NtpWiring.wire(WarpGuardService.this::say);
                    } else if (!WarpWeb.running()) {
                        Log.i(TAG, "guard: warp HTTP server down, restarting");
                        WarpWeb.start();
                    }
                    PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                    PowerManager.WakeLock wl = pm != null
                            ? pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "timewarp:guard")
                            : null;
                    if (wl != null) wl.acquire(30_000L);
                    try {
                        TimeEngine.Outcome o = engine.maybeSelfHeal();
                        if (o != null) {
                            Log.i(TAG, o.success ? "guard self-heal OK via " + o.method
                                    : "guard self-heal failed: " + o.detail);
                        }
                    } finally {
                        if (wl != null && wl.isHeld()) wl.release();
                    }
                }
            } catch (Throwable t) {
                Log.i(TAG, "guard tick crashed: " + t);
            }
            main.postDelayed(this, TICK_MS);
        }
    };

    private void say(String line) {
        Log.i(TAG, line);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        engine = new TimeEngine(this, this::say);
        createNotification();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Prefs.getLastTarget(this) <= 0) {
            stopSelf();
            return START_NOT_STICKY;
        }
        main.removeCallbacks(tick);
        main.post(tick);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        main.removeCallbacks(tick);
        if (Prefs.getLastTarget(this) <= 0) {
            SntpServer.stop();
            WarpWeb.stop();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL,
                    "TimeWarp active", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                this, 0, open, android.app.PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        Notification n = b.setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("TimeWarp active — clock is warped")
                .setContentText("System, apps and NTP all see it. Tap to manage.")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }
}