package com.elthon.timewarp;

import android.content.Context;
import android.os.SystemClock;

import java.util.Calendar;

/**
 * The actual time-warp engine.
 *
 * Model: offset = fakeSystemTime - realTime. Both clocks keep ticking at the
 * same rate afterwards, so realTime = systemTime - offset holds until we
 * change the system time again (then the offset is updated).
 *
 * Strategy chain (each step logged, first verified win is used):
 *   1. settings put global auto_time 0        (stop NTP snap-back)
 *   2. cmd alarm set-time <epoch_ms>          (AOSP shell cmd, shell has SET_TIME)
 *   3. cmd time_detector suggest_network_time (time detector path)
 *   4. toybox date MMDDhhmmCCYY.ss            (blocked on most ROMs, cheap to try)
 *   5. su paths                               (only when Shizuku runs as root)
 */
public final class TimeEngine {

    public interface Logger {
        void log(String line);
    }

    public static final class Outcome {
        public final boolean success;
        public final String method;
        public final String detail;

        Outcome(boolean success, String method, String detail) {
            this.success = success;
            this.method = method;
            this.detail = detail;
        }
    }

    private final Context app;
    private final Logger log;

    public TimeEngine(Context app, Logger log) {
        this.app = app.getApplicationContext();
        this.log = log;
    }

    private void say(String s) {
        if (log != null) log.log(s);
    }

    // ---------------------------------------------------------------- queries

    public long systemNow() {
        return System.currentTimeMillis();
    }

    /** Best-effort real time. Never throws, never hits the network. */
    public long realNowOffline() {
        long offset = Prefs.getOffset(app);
        if (offset == 0 || !Prefs.offsetLooksValid(app)) {
            // After a reboot/RTC reset the stored offset is stale - the system
            // clock is (usually) real again, so trust it over the old math.
            return systemNow();
        }
        return systemNow() - offset;
    }

    /**
     * Real time used when computing a new offset. If the stored offset looks
     * stale it tries to recalibrate from the network first (blocking - call on
     * a worker thread), otherwise falls back to trusting the system clock.
     */
    private long resolveRealForJump() {
        long offset = Prefs.getOffset(app);
        if (offset == 0) return systemNow();
        if (Prefs.offsetLooksValid(app)) return systemNow() - offset;

        say("    stored offset looks stale (reboot/RTC change), recalibrating...");
        try {
            RealTime.Result net = RealTime.fetch(4000);
            if (net != null) {
                long real = net.millis;
                Prefs.setOffset(app, systemNow() - real);
                say("    recalibrated from " + net.source + ", offset now "
                        + formatOffset(Prefs.getOffset(app)));
                return real;
            }
        } catch (Throwable ignored) {
        }
        say("    no network for recalibration - treating system clock as real");
        Prefs.setOffset(app, 0);
        return systemNow();
    }

    public long offset() {
        return Prefs.getOffset(app);
    }

    // ------------------------------------------------------------- mutations

    /** +1 calendar day from wherever the system clock currently is. */
    public synchronized Outcome jumpTomorrow() {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(systemNow());
        c.add(Calendar.DAY_OF_YEAR, 1);
        long target = c.getTimeInMillis();
        say(">>> Tomorrow: target = " + fmt(target));
        return setAbsolute(target, "+1 day");
    }

    /** Back to reality. Prefers a live network clock, falls back to stored offset. */
    public synchronized Outcome resetReal() {
        say(">>> Reset requested, fetching real time...");
        RealTime.Result net = null;
        try {
            net = RealTime.fetch(5000);
        } catch (Throwable ignored) {
        }

        long target;
        if (net != null) {
            target = net.millis;
            say("    real time from " + net.source + " = " + fmt(target));
        } else {
            target = realNowOffline();
            say("    network unavailable, using offset math = " + fmt(target));
        }
        Outcome o = setAbsolute(target, "reset");
        if (o.success) {
            Prefs.setOffset(app, 0);
            say("    offset cleared (0). You are on the real clock again.");
        }
        return o;
    }

    /** Arbitrary absolute time (timestamp button). */
    public synchronized Outcome jumpToMillis(long target, String label) {
        say(">>> Jump to " + fmt(target) + " (" + label + ")");
        return setAbsolute(target, label);
    }

    /**
     * Core setter. Recomputes the offset from the *current* real time so that
     * Reset keeps working after any jump.
     */
    private Outcome setAbsolute(long target, String label) {
        long real = resolveRealForJump();
        long newOffset = target - real;

        Outcome o = applySystemTime(target);

        if (o.success) {
            Prefs.setOffset(app, newOffset);
            Prefs.snapshotClocks(app);
            say("    OK via " + o.method + " | offset now " + formatOffset(newOffset));
        } else {
            say("    ALL STRATEGIES FAILED: " + o.detail);
        }
        return o;
    }

    // ----------------------------------------------------------- strategies

    private Outcome applySystemTime(long target) {
        if (!ShizukuRunner.isRunning()) {
            return new Outcome(false, "-", "shizuku server not running");
        }
        if (!ShizukuRunner.hasPermission()) {
            return new Outcome(false, "-", "shizuku permission not granted");
        }

        StringBuilder failures = new StringBuilder();

        // 0. Kill automatic time so nothing snaps the clock back.
        ShizukuRunner.Result a1 = ShizukuRunner.sh("settings put global auto_time 0");
        say("  settings put global auto_time 0 -> exit " + a1.exit + " " + a1.combined());
        ShizukuRunner.Result a2 = ShizukuRunner.sh("settings put global auto_time_zone 0");
        say("  settings put global auto_time_zone 0 -> exit " + a2.exit + " " + a2.combined());

        // 1. AOSP shell command (primary).
        ShizukuRunner.Result r1 = ShizukuRunner.sh("cmd alarm set-time " + target);
        say("  cmd alarm set-time " + target + " -> exit " + r1.exit + " " + r1.combined());
        if (r1.ok() && clockAt(target)) {
            broadcastTimeSetIfNeeded("cmd alarm set-time");
            return new Outcome(true, "cmd alarm set-time", r1.combined());
        }
        failures.append("[cmd alarm set-time: exit ").append(r1.exit)
                .append(" ").append(shortMsg(r1)).append("] ");

        // 2. time_detector network suggestion.
        long elapsed = SystemClock.elapsedRealtime();
        ShizukuRunner.Result r2 = ShizukuRunner.sh(
                "cmd time_detector suggest_network_time --unix_epoch_time " + target
                        + " --elapsed_realtime " + elapsed);
        say("  suggest_network_time -> exit " + r2.exit + " " + r2.combined());
        if (r2.ok() && clockAt(target)) {
            broadcastTimeSetIfNeeded("time_detector");
            return new Outcome(true, "time_detector suggest_network_time", r2.combined());
        }
        failures.append("[suggest_network_time: exit ").append(r2.exit)
                .append(" ").append(shortMsg(r2)).append("] ");

        // 3. toybox date (usually EPERM for shell, costs nothing to try).
        ShizukuRunner.Result r3 = ShizukuRunner.sh("date " + toyboxFormat(target));
        say("  date " + toyboxFormat(target) + " -> exit " + r3.exit + " " + r3.combined());
        if (r3.ok() && clockAt(target)) {
            ShizukuRunner.sh("am broadcast -a android.intent.action.TIME_SET");
            return new Outcome(true, "toybox date", r3.combined());
        }
        failures.append("[toybox date: exit ").append(r3.exit)
                .append(" ").append(shortMsg(r3)).append("] ");

        // 4. Root paths (only relevant when Shizuku was started with su).
        if (ShizukuRunner.uid() == 0) {
            ShizukuRunner.Result r4 = ShizukuRunner.sh("date " + toyboxFormat(target));
            say("  root date -> exit " + r4.exit + " " + r4.combined());
            if (r4.ok() && clockAt(target)) {
                ShizukuRunner.sh("am broadcast -a android.intent.action.TIME_SET");
                return new Outcome(true, "root date", r4.combined());
            }
            ShizukuRunner.Result r5 = ShizukuRunner.sh("su 0 date -s " + toyboxFormat(target));
            say("  su 0 date -s -> exit " + r5.exit + " " + r5.combined());
            if (r5.ok() && clockAt(target)) {
                ShizukuRunner.sh("am broadcast -a android.intent.action.TIME_SET");
                return new Outcome(true, "su date", r5.combined());
            }
            failures.append("[root date: ").append(shortMsg(r4)).append("] ");
        }

        return new Outcome(false, "-", failures.toString().trim());
    }

    private void broadcastTimeSetIfNeeded(String via) {
        // AlarmManagerService.setTime fires ACTION_TIME_CHANGED itself; this is
        // just insurance for ROMs that swallow it.
        ShizukuRunner.Result b = ShizukuRunner.sh("am broadcast -a android.intent.action.TIME_SET");
        say("  TIME_SET broadcast (" + via + ") -> exit " + b.exit);
    }

    /** True when the system clock actually landed within `tol` of target. */
    private boolean clockAt(long target) {
        return Math.abs(System.currentTimeMillis() - target) < 4000L;
    }

    /** toybox/toolbox SET format: MMDDhhmmCCYY.ss in local time. */
    static String toyboxFormat(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        return String.format(java.util.Locale.US,
                "%02d%02d%02d%02d%04d.%02d",
                c.get(Calendar.MONTH) + 1,
                c.get(Calendar.DAY_OF_MONTH),
                c.get(Calendar.HOUR_OF_DAY),
                c.get(Calendar.MINUTE),
                c.get(Calendar.YEAR),
                c.get(Calendar.SECOND));
    }

    // ------------------------------------------------------------- formatting

    public static String fmt(long millis) {
        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault());
        return f.format(new java.util.Date(millis));
    }

    public static String formatOffset(long offsetMs) {
        long days = offsetMs / 86_400_000L;
        long hours = Math.abs(offsetMs % 86_400_000L) / 3_600_000L;
        long mins = Math.abs(offsetMs % 3_600_000L) / 60_000L;
        String sign = offsetMs >= 0 ? "+" : "-";
        return sign + Math.abs(days) + "d " + hours + "h " + mins + "m";
    }

    private static String shortMsg(ShizukuRunner.Result r) {
        String m = r.combined().replace('\n', ' ').trim();
        if (m.isEmpty()) return r.exit == 0 ? "clock did not move" : "no output";
        return m.length() > 120 ? m.substring(0, 120) + "…" : m;
    }
}
