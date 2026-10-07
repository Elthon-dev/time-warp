package com.elthon.timewarp;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tiny "WarpWeb" HTTP responder. Any external device that hits
 * http://&lt;phone-ip&gt;:port/ sees the warped clock in the RFC 7231 Date
 * header AND a friendly human body - so a friend can literally {@code curl}
 * your phone and read "2027". Same trick as the SNTP server, but for humans.
 */
public final class WarpWeb {

    private static final int PREFERRED_PORT = 8088;
    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static volatile ServerSocket server;
    private static volatile Thread acceptThread;

    private WarpWeb() {
    }

    public static synchronized boolean start() {
        if (running.get()) return true;
        try {
            server = new ServerSocket(PREFERRED_PORT, 16, InetAddress.getByName("0.0.0.0"));
        } catch (Throwable t) {
            try {
                server = new ServerSocket(0, 16, InetAddress.getByName("0.0.0.0"));
            } catch (Throwable t2) {
                server = null;
                return false;
            }
        }
        ServerSocket s = server;
        running.set(true);
        acceptThread = new Thread(() -> serveLoop(s), "warp-web");
        acceptThread.setDaemon(true);
        acceptThread.start();
        return true;
    }

    public static synchronized void stop() {
        running.set(false);
        if (server != null) {
            try {
                server.close();
            } catch (Throwable ignored) {
            }
        }
        server = null;
    }

    public static boolean running() {
        return running.get();
    }

    public static int port() {
        ServerSocket s = server;
        return s != null ? s.getLocalPort() : 0;
    }

    private static void serveLoop(ServerSocket s) {
        while (running.get()) {
            try {
                Socket client = s.accept();
                handle(client);
            } catch (Throwable ignored) {
                // closed socket => exit; transient errors keep looping
            }
        }
    }

    private static void handle(Socket client) {
        try (Socket c = client) {
            c.setSoTimeout(4000);
            InputStream in = c.getInputStream();
            OutputStream out = c.getOutputStream();

            byte[] header = new byte[256];
            in.read(header); // request line; we ignore its contents

            long now = System.currentTimeMillis(); // the warped clock
            String body = body(now);
            byte[] bodyBytes = body.getBytes("UTF-8");
            byte[] head = ("HTTP/1.1 200 OK\r\n"
                    + "Date: " + rfc1123(now) + "\r\n"
                    + "Content-Type: text/plain; charset=utf-8\r\n"
                    + "Connection: close\r\n"
                    + "Content-Length: " + bodyBytes.length + "\r\n"
                    + "\r\n").getBytes("UTF-8");
            out.write(head);
            out.write(bodyBytes);
            out.flush();
        } catch (Throwable ignored) {
        }
    }

    private static String rfc1123(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("GMT"));
        return f.format(new Date(ms));
    }

    private static String body(long ms) {
        SimpleDateFormat local =
                new SimpleDateFormat("EEEE, MMMM dd, yyyy  hh:mm:ss aa zzz", Locale.getDefault());
        return "TimeWarp says it is currently:\n\n"
                + "  " + local.format(new Date(ms)) + "\n"
                + "  unix epoch ms: " + ms + "\n"
                + "  (this warps every app + the NTP/HTTP answers this phone serves)\n";
    }
}