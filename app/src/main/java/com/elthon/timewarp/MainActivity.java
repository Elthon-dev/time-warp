package com.elthon.timewarp;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {

    static final int REQ_SHIZUKU_PERMISSION = 4242;
    static final int REQ_POST_NOTIFICATIONS = 4343;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final StringBuilder logBuf = new StringBuilder();

    private TextView statusText, logText;
    private ScrollView logScroll;
    private EditText inpTimestamp, inpYear, inpMonth, inpDay, inpHour, inpMin;
    private Button btnTomorrow, btnReset, btnBubble;
    private TimeEngine engine;
    private PermissionCard permissionCard;
    private boolean busy;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            main.postDelayed(this, 1000);
        }
    };

    // ------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        logText = findViewById(R.id.logText);
        logScroll = findViewById(R.id.logScroll);
        inpTimestamp = findViewById(R.id.inpTimestamp);
        inpYear = findViewById(R.id.inpYear);
        inpMonth = findViewById(R.id.inpMonth);
        inpDay = findViewById(R.id.inpDay);
        inpHour = findViewById(R.id.inpHour);
        inpMin = findViewById(R.id.inpMin);
        btnTomorrow = findViewById(R.id.btnTomorrow);
        btnReset = findViewById(R.id.btnReset);
        btnBubble = findViewById(R.id.btnBubble);

        engine = new TimeEngine(this, this::appendLog);
        permissionCard = new PermissionCard(this);

        btnTomorrow.setOnClickListener(v -> runAction("tomorrow", () -> {
            TimeEngine.Outcome o = engine.jumpTomorrow();
            afterJump(o);
        }));
        btnReset.setOnClickListener(v -> runAction("reset", () -> {
            TimeEngine.Outcome o = engine.resetReal();
            afterJump(o);
        }));
        findViewById(R.id.btnJumpTs).setOnClickListener(v -> onTimestampJump());
        findViewById(R.id.btnJumpDate).setOnClickListener(v -> onDateJump());
        findViewById(R.id.btnPermissions).setOnClickListener(v -> permissionCard.show(false));
        btnBubble.setOnClickListener(v -> toggleBubble());

        prefillDateFields();

        appendLog("TimeWarp ready. Shizuku: "
                + ShizukuRunner.uidLabel() + (ShizukuRunner.hasPermission() ? ", granted" : ""));

        if (!Prefs.isFirstDone(this)) {
            main.postDelayed(() -> permissionCard.show(true), 350);
        }

        if (ShizukuRunner.isRunning() && ShizukuRunner.hasPermission()) {
            io.execute(() -> {
                try {
                    TimeEngine.Outcome h = engine.maybeSelfHeal();
                    if (h != null) {
                        appendLog(h.success ? "self-heal: back on target via " + h.method
                                : "self-heal failed: " + h.detail);
                    }
                } catch (Throwable t) {
                    appendLog("self-heal crashed: " + t);
                }
            });
        }

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addRequestPermissionResultListener(permissionResultListener);
        main.post(ticker);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeRequestPermissionResultListener(permissionResultListener);
        main.removeCallbacksAndMessages(null);
        io.shutdown();
    }

    // ------------------------------------------------------------- actions

    private interface Work {
        void run();
    }

    /** Single-flight: spam taps are ignored while an operation is running. */
    private void runAction(String name, Work work) {
        if (busy) {
            appendLog("⚠ '" + name + "' ignored - previous jump still running (spam guard)");
            return;
        }
        busy = true;
        setButtonsEnabled(false);
        io.execute(() -> {
            try {
                work.run();
            } catch (Throwable t) {
                appendLog("💥 " + name + " crashed: " + t);
            } finally {
                main.post(() -> {
                    busy = false;
                    setButtonsEnabled(true);
                    refreshStatus();
                    prefillDateFields();
                });
            }
        });
    }

    private void afterJump(TimeEngine.Outcome o) {
        main.post(() -> {
            if (o.success) {
                Toast.makeText(this, "Warped via " + o.method, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "FAILED - see log", Toast.LENGTH_LONG).show();
            }
        });
    }

    private void onTimestampJump() {
        String raw = inpTimestamp.getText().toString().trim();
        if (TextUtils.isEmpty(raw)) {
            Toast.makeText(this, "Enter a timestamp first", Toast.LENGTH_SHORT).show();
            return;
        }
        Long target = parseTimestamp(raw);
        if (target == null) {
            appendLog("⚠ cannot parse '" + raw + "' — use epoch ms (1767225600000) or 2026-12-25 08:00");
            Toast.makeText(this, "Unrecognized format", Toast.LENGTH_SHORT).show();
            return;
        }
        long t = target;
        runAction("timestamp", () -> afterJump(engine.jumpToMillis(t, "manual timestamp")));
    }

    private void onDateJump() {
        try {
            int y = Integer.parseInt(inpYear.getText().toString().trim());
            int mo = Integer.parseInt(inpMonth.getText().toString().trim());
            int d = Integer.parseInt(inpDay.getText().toString().trim());
            int h = inpHour.getText().toString().trim().isEmpty()
                    ? 12 : Integer.parseInt(inpHour.getText().toString().trim());
            int mi = inpMin.getText().toString().trim().isEmpty()
                    ? 0 : Integer.parseInt(inpMin.getText().toString().trim());

            Calendar c = Calendar.getInstance();
            c.set(y, mo - 1, d, h, mi, 0);
            c.set(Calendar.MILLISECOND, 0);
            if (c.get(Calendar.YEAR) != y || c.get(Calendar.MONTH) != mo - 1
                    || c.get(Calendar.DAY_OF_MONTH) != d) {
                appendLog("⚠ invalid date: " + y + "-" + mo + "-" + d);
                Toast.makeText(this, "Invalid date", Toast.LENGTH_SHORT).show();
                return;
            }
            long target = c.getTimeInMillis();
            runAction("date-set", () -> afterJump(engine.jumpToMillis(target, "manual date")));
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Fill year/month/day", Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleBubble() {
        if (Prefs.isBubbleEnabled(this)) {
            stopService(new Intent(this, BubbleService.class));
            Prefs.setBubbleEnabled(this, false);
            appendLog("bubble disabled");
        } else {
            if (!PermissionCard.canDrawOverlays(this) && ShizukuRunner.isRunning()
                    && ShizukuRunner.hasPermission()) {
                io.execute(() -> {
                    ShizukuRunner.Result r = ShizukuRunner.sh(
                            "appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow");
                    appendLog("appops SYSTEM_ALERT_WINDOW allow -> exit " + r.exit);
                    main.post(() -> startBubbleIfPossible());
                });
                return;
            }
            startBubbleIfPossible();
        }
        refreshBubbleButton();
    }

    private void startBubbleIfPossible() {
        if (!PermissionCard.canDrawOverlays(this)) {
            Toast.makeText(this, "Grant 'Display over other apps' first", Toast.LENGTH_LONG).show();
            permissionCard.show(false);
            return;
        }
        Prefs.setBubbleEnabled(this, true);
        startService(new Intent(this, BubbleService.class));
        appendLog("bubble enabled — drag it anywhere, tap for quick actions");
        refreshBubbleButton();
    }

    // ------------------------------------------------------------- status

    private void refreshStatus() {
        long sys = System.currentTimeMillis();
        long real = engine.realNowOffline();
        long offset = Prefs.getOffset(this);

        String shizuku;
        if (!ShizukuRunner.isRunning()) {
            shizuku = "SHIZUKU OFF";
        } else if (!ShizukuRunner.hasPermission()) {
            shizuku = "SHIZUKU: no permission";
        } else {
            shizuku = "SHIZUKU ok (" + ShizukuRunner.uidLabel() + ")";
        }

        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        String mode = offset == 0 ? "REAL TIME"
                : "WARPED " + TimeEngine.formatOffset(offset);

        statusText.setText(String.format(Locale.US,
                "%s\nsystem: %s\nreal:   %s\n%s",
                shizuku, f.format(new Date(sys)),
                offset == 0 ? f.format(new Date(sys)) : f.format(new Date(real)),
                mode));
        statusText.setTextColor(offset == 0 ? Color.parseColor("#1B5E20")
                : Color.parseColor("#B71C1C"));
        refreshBubbleButton();
    }

    private void refreshBubbleButton() {
        boolean on = Prefs.isBubbleEnabled(this);
        btnBubble.setText(on ? "🫧 Bubble: ON" : "🫧 Bubble: off");
    }

    private void setButtonsEnabled(boolean enabled) {
        btnTomorrow.setEnabled(enabled);
        btnReset.setEnabled(enabled);
        btnTomorrow.setAlpha(enabled ? 1f : 0.5f);
        btnReset.setAlpha(enabled ? 1f : 0.5f);
    }

    private void prefillDateFields() {
        Calendar c = Calendar.getInstance();
        inpYear.setText(String.valueOf(c.get(Calendar.YEAR)));
        inpMonth.setText(String.format(Locale.US, "%02d", c.get(Calendar.MONTH) + 1));
        inpDay.setText(String.format(Locale.US, "%02d", c.get(Calendar.DAY_OF_MONTH)));
        inpHour.setText(String.format(Locale.US, "%02d", c.get(Calendar.HOUR_OF_DAY)));
        inpMin.setText(String.format(Locale.US, "%02d", c.get(Calendar.MINUTE)));
    }

    // ----------------------------------------------------------------- log

    void appendLog(String line) {
        main.post(() -> {
            SimpleDateFormat f = new SimpleDateFormat("HH:mm:ss", Locale.US);
            logBuf.append(f.format(new Date()))
                    .append("  ").append(line).append('\n');
            if (logBuf.length() > 32_000) logBuf.delete(0, logBuf.length() - 24_000);
            logText.setText(logBuf.toString());
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    // ------------------------------------------------------------- parsing

    static Long parseTimestamp(String raw) {
        // pure digits: epoch seconds (10) or millis (13-16)
        if (raw.matches("\\d{10}")) return Long.parseLong(raw) * 1000L;
        if (raw.matches("\\d{13,17}")) return Long.parseLong(raw);

        String[] patterns = {
                "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd",
                "yyyy/MM/dd HH:mm:ss", "yyyy/MM/dd HH:mm", "yyyy/MM/dd",
                "dd.MM.yyyy HH:mm"
        };
        for (String p : patterns) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(p, Locale.US);
                f.setLenient(false);
                Date d = f.parse(raw);
                if (d != null) return d.getTime();
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    // ------------------------------------------------------------- shizuku

    private final rikka.shizuku.Shizuku.OnBinderReceivedListener binderReceivedListener =
            () -> {
                appendLog("shizuku binder received (" + ShizukuRunner.uidLabel() + ")");
                if (permissionCard != null) permissionCard.refresh();
                refreshStatus();
            };

    private final rikka.shizuku.Shizuku.OnRequestPermissionResultListener permissionResultListener =
            (requestCode, grantResult) -> main.post(() -> {
                boolean granted = grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED;
                appendLog("shizuku permission result: " + (granted ? "GRANTED" : "DENIED"));
                if (permissionCard != null) permissionCard.refresh();
                refreshStatus();
            });

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (permissionCard != null) permissionCard.refresh();
    }
}
