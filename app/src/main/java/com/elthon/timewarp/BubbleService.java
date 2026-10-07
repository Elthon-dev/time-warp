package com.elthon.timewarp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The floating bubble 🕐 - a draggable overlay with quick actions:
 *   tap          → open the mini panel (+1 day / reset / close)
 *   drag         → move it anywhere
 *   long-press   → hide the bubble
 */
public class BubbleService extends Service {

    private static final String CHANNEL = "timewarp_bubble";
    private static final int NOTIF_ID = 7;

    private WindowManager wm;
    private View bubbleView;
    private View panelView;
    private TimeEngine engine;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private boolean busy;
    private int screenWidth, screenHeight;

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        engine = new TimeEngine(this, line -> {
            android.util.Log.i("TimeWarp", line);
        });

        if (!PermissionCard.canDrawOverlays(this)) {
            Toast.makeText(this, "Overlay permission lost — bubble disabled", Toast.LENGTH_LONG).show();
            stopSelf();
            return;
        }

        Point size = new Point();
        wm.getDefaultDisplay().getSize(size);
        screenWidth = size.x;
        screenHeight = size.y;

        createNotification();
        buildBubble();
        maybeTutorial();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        removePanel();
        if (bubbleView != null) {
            try {
                wm.removeView(bubbleView);
            } catch (Throwable ignored) {
            }
            bubbleView = null;
        }
        io.shutdownNow();
        Prefs.setBubbleEnabled(this, false);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------ notification

    private void createNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL,
                    "TimeWarp bubble", NotificationManager.IMPORTANCE_LOW);
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
                .setContentTitle("TimeWarp bubble active")
                .setContentText("Tap to open TimeWarp")
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

    // ------------------------------------------------------------------ bubble

    private void buildBubble() {
        int size = dp(54);
        TextView tv = new TextView(this);
        tv.setText("🕐");
        tv.setTextSize(24f);
        tv.setGravity(Gravity.CENTER);
        tv.setBackgroundResource(R.drawable.bg_bubble);
        tv.setElevation(dp(6));

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        p.x = screenWidth - size - dp(12);
        p.y = screenHeight / 3;

        tv.setOnTouchListener(new BubbleTouch(p));
        bubbleView = tv;
        wm.addView(bubbleView, p);
    }

    private final class BubbleTouch implements View.OnTouchListener {
        private final WindowManager.LayoutParams params;
        private float startX, startY;
        private int startParamX, startParamY;
        private boolean dragging;
        private final Runnable longPress = () -> {
            Toast.makeText(BubbleService.this, "Bubble hidden", Toast.LENGTH_SHORT).show();
            stopSelf();
        };

        BubbleTouch(WindowManager.LayoutParams params) {
            this.params = params;
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = e.getRawX();
                    startY = e.getRawY();
                    startParamX = params.x;
                    startParamY = params.y;
                    dragging = false;
                    main.postDelayed(longPress, 750);
                    return true;

                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - startX;
                    float dy = e.getRawY() - startY;
                    if (!dragging && (Math.abs(dx) > dp(8) || Math.abs(dy) > dp(8))) {
                        dragging = true;
                        main.removeCallbacks(longPress);
                    }
                    if (dragging) {
                        params.x = clamp((int) (startParamX + dx), -dp(10), screenWidth - dp(44));
                        params.y = clamp((int) (startParamY + dy), dp(60), screenHeight - dp(60));
                        try {
                            wm.updateViewLayout(bubbleView, params);
                        } catch (Throwable ignored) {
                        }
                    }
                    return true;
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    main.removeCallbacks(longPress);
                    if (!dragging && e.getActionMasked() == MotionEvent.ACTION_UP) {
                        togglePanel(params);
                    }
                    return true;
            }
            return false;
        }
    }

    // ------------------------------------------------------------------- panel

    private void togglePanel(WindowManager.LayoutParams bubbleParams) {
        if (panelView != null) {
            removePanel();
            return;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x33000000);

        FrameLayout inner = new FrameLayout(this);
        inner.setBackgroundResource(R.drawable.bg_panel);
        inner.setPadding(dp(14), dp(10), dp(14), dp(10));

        LinearLayoutHolder holder = buildPanelButtons();

        FrameLayout.LayoutParams lp =
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        lp.leftMargin = clamp(bubbleParams.x - dp(150), dp(8), screenWidth - dp(230));
        lp.topMargin = clamp(bubbleParams.y + dp(62), dp(70), screenHeight - dp(200));
        inner.addView(holder.layout, lp);
        root.addView(inner);

        // tap anywhere outside the panel dismisses it
        root.setOnClickListener(v -> removePanel());

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        p.x = 0;
        p.y = 0;

        panelView = root;
        try {
            wm.addView(panelView, p);
        } catch (Throwable t) {
            panelView = null;
            Toast.makeText(this, "Cannot show panel: " + t, Toast.LENGTH_SHORT).show();
        }
    }

    private static final class LinearLayoutHolder {
        android.widget.LinearLayout layout;
    }

    private LinearLayoutHolder buildPanelButtons() {
        LinearLayoutHolder h = new LinearLayoutHolder();
        android.widget.LinearLayout ll = new android.widget.LinearLayout(this);
        ll.setOrientation(android.widget.LinearLayout.VERTICAL);

        h.layout = ll;
        ll.addView(panelButton("⏩  Jump +1 day", v -> runQuick("tomorrow", () -> engine.jumpTomorrow())));
        ll.addView(panelButton("⏮  Reset to real now", v -> runQuick("reset", () -> engine.resetReal())));
        ll.addView(panelButton("✕  Hide bubble", v -> stopSelf()));
        return h;
    }

    private Button panelButton(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(14f);
        b.setOnClickListener(l);
        return b;
    }

    private interface Quick {
        TimeEngine.Outcome run();
    }

    private void runQuick(String name, Quick q) {
        if (busy) {
            Toast.makeText(this, "Still working on the last jump…", Toast.LENGTH_SHORT).show();
            return;
        }
        busy = true;
        removePanel();
        io.execute(() -> {
            TimeEngine.Outcome o = null;
            try {
                o = q.run();
            } catch (Throwable t) {
                final String msg = "failed: " + t;
                main.post(() -> Toast.makeText(BubbleService.this, msg, Toast.LENGTH_LONG).show());
            }
            final TimeEngine.Outcome res = o;
            main.post(() -> {
                busy = false;
                if (res != null) {
                    Toast.makeText(BubbleService.this,
                            res.success
                                    ? "Warped → " + TimeEngine.fmt(System.currentTimeMillis())
                                    : "FAILED — open TimeWarp for the log",
                            Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void removePanel() {
        if (panelView != null) {
            try {
                wm.removeView(panelView);
            } catch (Throwable ignored) {
            }
            panelView = null;
        }
    }

    // ---------------------------------------------------------------- helpers

    private void maybeTutorial() {
        if (!Prefs.isBubbleTutorialSeen(this)) {
            Prefs.setBubbleTutorialSeen(this);
            Toast.makeText(this,
                    "Bubble active: drag to move · tap for actions · long-press to hide",
                    Toast.LENGTH_LONG).show();
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
