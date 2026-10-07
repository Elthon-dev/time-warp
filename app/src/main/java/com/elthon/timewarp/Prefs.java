package com.elthon.timewarp;

import android.content.Context;
import android.content.SharedPreferences;

public final class Prefs {
    private static final String NAME = "timewarp";
    private static final String K_OFFSET = "offset_ms";
    private static final String K_FIRST_DONE = "first_launch_done";
    private static final String K_LAST_SYS = "last_sys_ms";
    private static final String K_LAST_ELAPSED = "last_elapsed_ms";
    private static final String K_BUBBLE = "bubble_enabled";
    private static final String K_SEEN_TUTORIAL = "bubble_tutorial_seen";
    private static final String K_LAST_TARGET = "last_target_ms";

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static long getOffset(Context c) {
        return sp(c).getLong(K_OFFSET, 0L);
    }

    public static void setOffset(Context c, long offset) {
        sp(c).edit().putLong(K_OFFSET, offset).apply();
    }

    public static boolean isFirstDone(Context c) {
        return sp(c).getBoolean(K_FIRST_DONE, false);
    }

    public static void setFirstDone(Context c, boolean v) {
        sp(c).edit().putBoolean(K_FIRST_DONE, v).apply();
    }

    public static boolean isBubbleEnabled(Context c) {
        return sp(c).getBoolean(K_BUBBLE, false);
    }

    public static void setBubbleEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean(K_BUBBLE, v).apply();
    }

    public static boolean isBubbleTutorialSeen(Context c) {
        return sp(c).getBoolean(K_SEEN_TUTORIAL, false);
    }

    public static void setBubbleTutorialSeen(Context c) {
        sp(c).edit().putBoolean(K_SEEN_TUTORIAL, true).apply();
    }

    /** Snapshot the clocks so we can detect reboots / RTC resets later. */
    public static void snapshotClocks(Context c) {
        sp(c).edit()
                .putLong(K_LAST_SYS, System.currentTimeMillis())
                .putLong(K_LAST_ELAPSED, android.os.SystemClock.elapsedRealtime())
                .apply();
    }

    /**
     * @return true if the stored offset is still trustworthy (no reboot / RTC reset
     * detected between the last snapshot and now).
     */
    public static boolean offsetLooksValid(Context c) {
        SharedPreferences p = sp(c);
        long lastSys = p.getLong(K_LAST_SYS, -1L);
        long lastElapsed = p.getLong(K_LAST_ELAPSED, -1L);
        if (lastSys <= 0 || lastElapsed < 0) return false;
        long nowElapsed = android.os.SystemClock.elapsedRealtime();
        long predicted = lastSys + (nowElapsed - lastElapsed);
        long actual = System.currentTimeMillis();
        // Both clocks advanced identically since the snapshot unless something
        // external (reboot, RTC reset, another time-changer) interfered.
        return Math.abs(predicted - actual) < 10 * 60 * 1000L;
    }

    /** Absolute system-clock target of the last successful warp; 0 = on real time. */
    public static long getLastTarget(Context c) {
        return sp(c).getLong(K_LAST_TARGET, 0L);
    }

    public static void setLastTarget(Context c, long targetMs) {
        sp(c).edit().putLong(K_LAST_TARGET, targetMs).apply();
    }

    public static void clearLastTarget(Context c) {
        sp(c).edit().putLong(K_LAST_TARGET, 0L).apply();
    }
}
