package com.elthon.timewarp;

/**
 * Points the device's own NTP plumbing at the in-app warp SNTP server so the
 * system believes the warped clock is "network truth" - even the networking
 * layer gets gaslit. Uses the Android 13+ test-only overrides on
 * network_time_update_service (SET_TIME, which shell already holds).
 * Cleared on reboot by design, so ClockGuard re-applies it after every reset.
 */
public final class NtpWiring {

    private NtpWiring() {
    }

    /** Warp mode: start the server, redirect system NTP at it, force a refresh, auto_time on. */
    public static void wire(TimeEngine.Logger log) {
        if (!SntpServer.start()) {
            log.log("  warp SNTP server bind FAILED - staying on auto_time 0 (guard mode)");
            return;
        }
        int port = SntpServer.port();
        log.log("  warp SNTP server: 0.0.0.0:" + port + " (serving the warped clock)");

        if (WarpWeb.start()) {
            log.log("  warp HTTP server: 0.0.0.0:" + WarpWeb.port()
                    + " (any device sees the warp via Date header/body)");
            log.log("  friend check: curl http://" + SntpServer.lanIp() + ":" + WarpWeb.port() + "/");
        } else {
            log.log("  warp HTTP server bind FAILED (still serving NTP only)");
        }

        boolean configured = false;
        String[] attempts = {
                "cmd network_time_update_service set_server_config_for_tests"
                        + " --server ntp://127.0.0.1:" + port + " --timeout_millis 3000",
                "cmd network_time_update_service set_server_config"
                        + " --hostname 127.0.0.1 --port " + port + " --timeout_millis 3000"
        };
        for (String cmd : attempts) {
            ShizukuRunner.Result r = ShizukuRunner.sh(cmd);
            log.log("  " + cmd + " -> exit " + r.exit + " " + r.combined());
            if (r.ok() && !suspicious(r)) {
                configured = true;
                break;
            }
        }
        if (!configured) {
            log.log("  network_time_update_service absent (pre-T ROM?) - staying in guard mode");
            return;
        }

        ShizukuRunner.Result fr = ShizukuRunner.sh("cmd network_time_update_service force_refresh");
        String frOut = fr.ok() ? fr.combined() : ("exit " + fr.exit);
        log.log("  force_refresh -> " + frOut);
        if (frOut.toLowerCase().contains("true")) {
            ShizukuRunner.Result a = ShizukuRunner.sh("settings put global auto_time 1");
            log.log("  auto_time back ON, NTP source = our warp -> exit " + a.exit);
            log.log("  network time now BELIEVES " + TimeEngine.fmt(System.currentTimeMillis()));
        } else {
            log.log("  force_refresh didn't confirm true - keeping auto_time OFF");
        }
        log.log("  LAN clients can sync: cmd network_time_update_service set_server_config_for_tests"
                + " --server ntp://" + SntpServer.lanIp() + ":" + port + " --timeout_millis 3000");
    }

    /** Real mode: reset NTP override, refresh from the real world, auto_time on. */
    public static void unwire(TimeEngine.Logger log) {
        ShizukuRunner.Result r1 = ShizukuRunner.sh(
                "cmd network_time_update_service reset_server_config_for_tests");
        if (!r1.ok()) {
            ShizukuRunner.sh("cmd network_time_update_service set_server_config_for_tests");
        }
        log.log("  ntp test config reset -> exit " + r1.exit);
        ShizukuRunner.Result fr = ShizukuRunner.sh("cmd network_time_update_service force_refresh");
        log.log("  force_refresh (back to real NTP) -> "
                + (fr.ok() ? fr.combined() : "exit " + fr.exit));
        ShizukuRunner.Result a = ShizukuRunner.sh("settings put global auto_time 1");
        log.log("  auto_time ON (real time again) -> exit " + a.exit);
        SntpServer.stop();
        WarpWeb.stop();
        log.log("  warp SNTP + HTTP servers stopped");
    }

    private static boolean suspicious(ShizukuRunner.Result r) {
        String c = r.combined().toLowerCase();
        return c.contains("unrecognized") || c.contains("exception") || c.contains("error");
    }
}