package com.elthon.timewarp;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * First-launch floating permission card.
 *
 * Rows 1-3 are required (Done unlocks when green). Shizuku is asked through
 * its own API dialog; the overlay permission is granted instantly via
 * `appops set` when Shizuku is ready, with a Settings fallback otherwise.
 */
public final class PermissionCard {

    private static final int GREEN = 0xFF2E7D32;
    private static final int RED = 0xFFE53935;
    private static final int GREY = 0xFFB0BEC5;

    private final MainActivity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Dialog dialog;
    private boolean firstLaunch;
    private long lastBatteryCheck;
    private String batteryCache = "";

    private TextView dotRun, dotPerm, dotOverlay, dotBattery, dotNotif;
    private TextView subRun, subPerm, subOverlay, subBattery, subNotif;
    private Button btnRun, btnPerm, btnOverlay, btnBattery, btnNotif, btnDone;

    private final Runnable poller = new Runnable() {
        @Override
        public void run() {
            if (dialog == null || !dialog.isShowing()) return;
            refresh();
            handler.postDelayed(this, 800);
        }
    };

    public PermissionCard(MainActivity activity) {
        this.activity = activity;
    }

    public static boolean canDrawOverlays(Context c) {
        return Settings.canDrawOverlays(c);
    }

    public void show(boolean isFirstLaunch) {
        firstLaunch = isFirstLaunch;
        if (dialog != null && dialog.isShowing()) {
            refresh();
            return;
        }
        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_permissions);
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);

        dotRun = dialog.findViewById(R.id.dotShizukuRun);
        dotPerm = dialog.findViewById(R.id.dotShizukuPerm);
        dotOverlay = dialog.findViewById(R.id.dotOverlay);
        dotBattery = dialog.findViewById(R.id.dotBattery);
        dotNotif = dialog.findViewById(R.id.dotNotif);
        subRun = dialog.findViewById(R.id.subShizukuRun);
        subPerm = dialog.findViewById(R.id.subShizukuPerm);
        subOverlay = dialog.findViewById(R.id.subOverlay);
        subBattery = dialog.findViewById(R.id.subBattery);
        subNotif = dialog.findViewById(R.id.subNotif);
        btnRun = dialog.findViewById(R.id.btnShizukuRun);
        btnPerm = dialog.findViewById(R.id.btnShizukuPerm);
        btnOverlay = dialog.findViewById(R.id.btnOverlay);
        btnBattery = dialog.findViewById(R.id.btnBattery);
        btnNotif = dialog.findViewById(R.id.btnNotif);
        btnDone = dialog.findViewById(R.id.btnSetupDone);

        LinearLayout rowNotif = dialog.findViewById(R.id.rowNotif);
        rowNotif.setVisibility(Build.VERSION.SDK_INT >= 33 ? View.VISIBLE : View.GONE);

        btnRun.setOnClickListener(v -> openShizuku());
        btnPerm.setOnClickListener(v -> {
            try {
                rikka.shizuku.Shizuku.requestPermission(MainActivity.REQ_SHIZUKU_PERMISSION);
            } catch (Throwable t) {
                Toast.makeText(activity, "Start Shizuku first", Toast.LENGTH_SHORT).show();
            }
        });
        btnOverlay.setOnClickListener(v -> grantOverlay());
        btnBattery.setOnClickListener(v -> grantBattery());
        btnNotif.setOnClickListener(v -> activity.requestPermissions(
                new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                MainActivity.REQ_POST_NOTIFICATIONS));
        dialog.findViewById(R.id.btnSetupDone).setOnClickListener(v -> finish(true));
        dialog.findViewById(R.id.btnSetupSkip).setOnClickListener(v -> finish(false));

        dialog.show();
        refresh();
        handler.postDelayed(poller, 800);
    }

    public void refresh() {
        if (dialog == null || !dialog.isShowing()) return;

        // 1 - shizuku running
        boolean running = ShizukuRunner.isRunning();
        setRow(dotRun, subRun, btnRun, running,
                running ? "running as " + ShizukuRunner.uidLabel() : "not running",
                running ? "Recheck" : "Open");

        // 2 - permission
        boolean perm = running && ShizukuRunner.hasPermission();
        setRow(dotPerm, subPerm, btnPerm, perm,
                perm ? "granted" : (running ? "tap to allow" : "needs step 1 first"),
                perm ? "Recheck" : "Grant");

        // 3 - overlay
        boolean overlay = canDrawOverlays(activity);
        setRow(dotOverlay, subOverlay, btnOverlay, overlay,
                overlay ? "granted — bubble can float" : "needed for the floating bubble",
                overlay ? "Recheck" : (running && ShizukuRunner.hasPermission()
                        ? "Allow (instant)" : "Open settings"));

        // 4 - battery (throttled, it shells out)
        long now = System.currentTimeMillis();
        if (now - lastBatteryCheck > 4000) {
            lastBatteryCheck = now;
            final boolean wasProtected = batteryCache.contains(activity.getPackageName());
            io().execute(() -> {
                ShizukuRunner.Result r = ShizukuRunner.sh("dumpsys deviceidle");
                final boolean protectedNow = r.combined().contains(activity.getPackageName());
                handler.post(() -> {
                    batteryCache = protectedNow ? "yes" : "";
                    if (dialog != null && dialog.isShowing()) {
                        setRow(dotBattery, subBattery, btnBattery, protectedNow,
                                protectedNow ? "protected from doze" : "let the app sleep in background",
                                protectedNow ? "OK ✓" : "Protect");
                    }
                });
            });
        }
        boolean batteryOk = batteryCache.contains(activity.getPackageName());
        setRow(dotBattery, subBattery, btnBattery, batteryOk,
                batteryOk ? "protected from doze" : "let the app sleep in background",
                batteryOk ? "OK ✓" : "Protect");

        // 5 - notifications
        if (Build.VERSION.SDK_INT >= 33) {
            boolean notif = activity.checkSelfPermission(
                    android.Manifest.permission.POST_NOTIFICATIONS)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
            setRow(dotNotif, subNotif, btnNotif, notif,
                    notif ? "granted" : "optional — bubble notice",
                    notif ? "OK ✓" : "Allow");
        }

        boolean ready = running && perm && overlay;
        btnDone.setEnabled(ready);
        btnDone.setAlpha(ready ? 1f : 0.45f);

        if (ready && firstLaunch) {
            firstLaunch = false;
            handler.postDelayed(() -> {
                if (dialog != null && dialog.isShowing()) finish(true);
            }, 700);
        }
    }

    private void setRow(TextView dot, TextView sub, Button btn, boolean ok,
                        String status, String action) {
        dot.setTextColor(ok ? GREEN : (ShizukuRunner.isRunning() ? RED : GREY));
        sub.setText(status);
        btn.setText(action);
    }

    private void finish(boolean savedFlag) {
        if (savedFlag || firstLaunch) Prefs.setFirstDone(activity, true);
        handler.removeCallbacks(poller);
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        dialog = null;
        activity.appendLog(savedFlag
                ? "setup finished ✅ — go warp some time"
                : "setup skipped (open Permissions anytime)");
    }

    private void openShizuku() {
        try {
            Intent i = activity.getPackageManager()
                    .getLaunchIntentForPackage("moe.shizuku.privileged.api");
            if (i != null) {
                activity.startActivity(i);
                return;
            }
        } catch (Throwable ignored) {
        }
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://shizuku.rikka.app/download/")));
        } catch (Throwable ignored) {
        }
        Toast.makeText(activity,
                "Install Shizuku first, then start it via Wireless Debugging",
                Toast.LENGTH_LONG).show();
    }

    private void grantOverlay() {
        if (canDrawOverlays(activity)) {
            refresh();
            return;
        }
        if (ShizukuRunner.isRunning() && ShizukuRunner.hasPermission()) {
            io().execute(() -> {
                ShizukuRunner.Result r = ShizukuRunner.sh(
                        "appops set " + activity.getPackageName() + " SYSTEM_ALERT_WINDOW allow");
                activity.appendLog("appops SYSTEM_ALERT_WINDOW allow -> exit " + r.exit
                        + " " + r.combined());
                handler.post(this::refresh);
            });
        } else {
            try {
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + activity.getPackageName())));
            } catch (Throwable t) {
                Toast.makeText(activity, "Open Settings → Special access → Display over other apps",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void grantBattery() {
        if (ShizukuRunner.isRunning() && ShizukuRunner.hasPermission()) {
            io().execute(() -> {
                ShizukuRunner.Result r = ShizukuRunner.sh(
                        "dumpsys deviceidle whitelist +" + activity.getPackageName());
                activity.appendLog("deviceidle whitelist + -> exit " + r.exit);
                batteryCache = "";
                handler.post(this::refresh);
            });
        } else {
            try {
                activity.startActivity(new Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + activity.getPackageName())));
            } catch (Throwable t) {
                Toast.makeText(activity, "Allow battery optimization exemption in Settings",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private static java.util.concurrent.ExecutorService io() {
        return Holder.IO;
    }

    private static final class Holder {
        static final java.util.concurrent.ExecutorService IO =
                java.util.concurrent.Executors.newSingleThreadExecutor();
    }
}
