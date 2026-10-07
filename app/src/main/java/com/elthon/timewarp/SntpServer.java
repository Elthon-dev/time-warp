package com.elthon.timewarp;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tiny SNTPv4 (RFC 4330) unicast responder. It serves whatever the kernel
 * CLOCK_REALTIME currently says - which TimeWarp keeps warped - so the system's
 * own network-time plumbing (network_time_update_service, time_detector,
 * SystemClock.currentNetworkTimeClock, any NTP-happy LAN device) comes to
 * believe the warp IS the real NTP truth. Root-free: binds a high UDP port
 * instead of the reserved 123.
 */
public final class SntpServer {
    private static final long NTP_EPOCH_SECS = 2208988800L; // unix seconds at NTP era 0

    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static volatile DatagramSocket socket;

    private SntpServer() {
    }

    public static synchronized boolean start() {
        if (running.get()) return true;
        try {
            socket = new DatagramSocket(0, InetAddress.getByName("0.0.0.0"));
        } catch (Throwable t) {
            socket = null;
            return false;
        }
        DatagramSocket s = socket;
        running.set(true);
        Thread th = new Thread(() -> serveLoop(s), "sntp-warp");
        th.setDaemon(true);
        th.start();
        return true;
    }

    public static synchronized void stop() {
        running.set(false);
        if (socket != null) {
            try {
                socket.close();
            } catch (Throwable ignored) {
            }
        }
        socket = null;
    }

    public static boolean running() {
        return running.get();
    }

    public static int port() {
        DatagramSocket s = socket;
        return s != null ? s.getLocalPort() : 0;
    }

    /** First non-loopback IPv4, for telling LAN clients where to sync from. */
    public static String lanIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "127.0.0.1";
    }

    private static void serveLoop(DatagramSocket s) {
        byte[] buf = new byte[512];
        while (running.get()) {
            try {
                DatagramPacket req = new DatagramPacket(buf, buf.length);
                s.receive(req);
                if (req.getLength() < 48) continue;
                long now = System.currentTimeMillis(); // the warped kernel clock
                byte[] resp = buildReply(buf, now);
                DatagramPacket out = new DatagramPacket(resp, resp.length,
                        req.getAddress(), req.getPort());
                s.send(out);
            } catch (Throwable ignored) {
                // socket closed = exit loop via running flag; transient errors keep looping
            }
        }
    }

    static byte[] buildReply(byte[] req, long nowMs) {
        byte[] r = new byte[48];
        r[0] = 0x24;            // LI=0, VN=4, Mode=4 (server)
        r[1] = 1;               // stratum 1: we ARE the reference clock
        r[2] = 4;               // poll exponent: 2^4 s
        r[3] = (byte) 0xEC;     // precision: 2^-20 s (~1us)
        // root delay (4 bytes) + root dispersion (4 bytes) left at 0
        r[12] = 'L';            // reference identifier: LOCL
        r[13] = 'O';
        r[14] = 'C';
        r[15] = 'L';

        long ntp = ((nowMs / 1000L + NTP_EPOCH_SECS) & 0xFFFFFFFFL) << 32;
        long frac = ((long) (nowMs % 1000L) << 32) / 1000L;

        putLong(r, 16, ((ntp - (1L << 32)) | frac)); // reference: ~1s in the past
        System.arraycopy(req, 40, r, 24, 8);         // originate: echo client's
        putLong(r, 32, ntp | frac);                  // receive: warped now
        putLong(r, 40, ntp | frac);                  // transmit: warped now
        return r;
    }

    private static void putLong(byte[] b, int off, long v) {
        for (int i = 0; i < 8; i++) {
            b[off + i] = (byte) (v >>> (56 - 8 * i));
        }
    }
}