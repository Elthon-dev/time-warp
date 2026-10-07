package com.elthon.timewarp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

/**
 * Keeps the system inherited on real time: as soon as the boot finishes (or the
 * clock is yanked away from a stored warp by auto-time / another process), it
 * pounds the kernel clock back onto the stored target via Shizuku.
 */
public class ClockGuardReceiver extends BroadcastReceiver {

    private static final String TAG = "TimeWarp";
    private static final long DEBOUNCE_MS = 5_000L;   // ignore our own TIME_CHANGED
    private static final long DRIFT_MS = 60_000L;     // only fight changes > 1 min

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_TIME_CHANGED.equals(action)) {
            return;
        }
        final Context c = context.getApplicationContext();

        // A TIME_CHANGED fired by our own set (within the debounce window) or
        // while we're still on the stored target is news, not a threat.
        if (Intent.ACTION_TIME_CHANGED.equals(action)) {
            long sinceApply = TimeEngine.lastApplyWall == 0
                    ? Long.MAX_VALUE
                    : SystemClock.elapsedRealtime() - TimeEngine.lastApplyWall;
            if (sinceApply < DEBOUNCE_MS) return;
            long target = Prefs.getLastTarget(c);
            if (target <= 0) return; // on real time; nothing to enforce
            if (Math.abs(System.currentTimeMillis() - target) < DRIFT_MS) return;
        }

        if (!ShizukuRunner.isRunning() || !ShizukuRunner.hasPermission()) return;

        final TimeEngine engine = new TimeEngine(c, msg -> Log.i(TAG, msg));
        new Thread(() -> {
            try {
                TimeEngine.Outcome o = engine.reapplyLastWarp();
                Log.i(TAG, "guard " + (o.success ? "OK via " + o.method : "failed: " + o.detail));
            } catch (Throwable t) {
                Log.i(TAG, "guard crashed: " + t);
            }
        }, "clock-guard").start();
    }
}