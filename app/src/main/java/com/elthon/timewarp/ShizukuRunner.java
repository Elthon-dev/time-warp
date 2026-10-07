package com.elthon.timewarp;

import android.content.pm.PackageManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.IShizukuService;
import rikka.shizuku.Shizuku;

/**
 * Thin wrapper around Shizuku.newProcess (API 13.1.5).
 * Every command result carries exit code + stdout + stderr so the on-screen
 * log can show exactly what the system said.
 */
public final class ShizukuRunner {

    public static final class Result {
        public final int exit;
        public final String out;
        public final String err;

        public Result(int exit, String out, String err) {
            this.exit = exit;
            this.out = out == null ? "" : out;
            this.err = err == null ? "" : err;
        }

        public boolean ok() {
            return exit == 0;
        }

        public String combined() {
            StringBuilder sb = new StringBuilder();
            if (!out.isEmpty()) sb.append(out.trim());
            if (!err.isEmpty()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(err.trim());
            }
            return sb.toString();
        }
    }

    private ShizukuRunner() {
    }

    public static boolean isRunning() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean hasPermission() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 2000 = adb/shell, 0 = root, -1 = unknown/unavailable. */
    public static int uid() {
        try {
            if (!isRunning()) return -1;
            return Shizuku.getUid();
        } catch (Throwable t) {
            return -1;
        }
    }

    public static String uidLabel() {
        int u = uid();
        if (u == 0) return "root (uid 0)";
        if (u == 2000) return "shell (uid 2000)";
        if (u == -1) return "unavailable";
        return "uid " + u;
    }

    public static Result sh(String command) {
        return exec(new String[]{"sh", "-c", command});
    }

    public static Result exec(String[] argv) {
        if (!isRunning()) return new Result(-1, "", "shizuku server not running");
        if (!hasPermission()) return new Result(-1, "", "shizuku permission not granted");

        Process process;
        try {
            IShizukuService service = IShizukuService.Stub.asInterface(Shizuku.getBinder());
            if (service == null) {
                return new Result(-1, "", "shizuku service binder is null");
            }
            process = service.newProcess(argv, null, null);
        } catch (Throwable t) {
            return new Result(-1, "", "newProcess failed: " + t);
        }

        StreamGobbler outG = new StreamGobbler(process.getInputStream());
        StreamGobbler errG = new StreamGobbler(process.getErrorStream());
        outG.start();
        errG.start();

        int exit;
        try {
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Result(-1, outG.get(), "TIMEOUT after 15s\n" + errG.get());
            }
            exit = process.exitValue();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return new Result(-1, outG.get(), "interrupted\n" + errG.get());
        }

        outG.joinQuietly();
        errG.joinQuietly();
        return new Result(exit, outG.get(), errG.get());
    }

    private static final class StreamGobbler extends Thread {
        private final InputStream in;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

        StreamGobbler(InputStream in) {
            this.in = in;
            setDaemon(true);
        }

        @Override
        public void run() {
            byte[] tmp = new byte[4096];
            try {
                int n;
                while ((n = in.read(tmp)) != -1) {
                    buf.write(tmp, 0, n);
                    if (buf.size() > 64 * 1024) break; // paranoia
                }
            } catch (Throwable ignored) {
            }
        }

        String get() {
            return buf.toString();
        }

        void joinQuietly() {
            try {
                join(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
