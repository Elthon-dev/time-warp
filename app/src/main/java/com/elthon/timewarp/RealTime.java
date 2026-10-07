package com.elthon.timewarp;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Recovers the REAL wall clock while the system clock is faked.
 *
 * Plain HTTP (port 80) is used on purpose: if we jumped the clock into the
 * future, HTTPS/TLS would fail with "certificate expired". An HTTP response
 * carries a Date header with zero TLS involved, redirects are NOT followed so
 * we can read it even from a 301.
 */
public final class RealTime {

    private static final String[] HOSTS = {
            "http://www.google.com/",
            "http://www.msftconnecttest.com/connecttest.txt",
            "http://example.com/"
    };

    private RealTime() {
    }

    public static final class Result {
        public final long millis;
        public final String source;

        Result(long millis, String source) {
            this.millis = millis;
            this.source = source;
        }
    }

    /** Blocking network call - run it on a background thread. */
    public static Result fetch(int timeoutMs) {
        for (String host : HOSTS) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(host).openConnection();
                conn.setConnectTimeout(timeoutMs);
                conn.setReadTimeout(timeoutMs);
                conn.setRequestMethod("HEAD");
                conn.setInstanceFollowRedirects(false);
                conn.setRequestProperty("User-Agent", "TimeWarp");
                int code = conn.getResponseCode();
                String date = conn.getHeaderField("Date");
                conn.disconnect();
                if (date != null) {
                    long parsed = parseRfc1123(date);
                    if (parsed > 0) return new Result(parsed, host + " (" + code + ")");
                }
            } catch (Throwable ignored) {
                // try next host
            }
        }
        return null;
    }

    static long parseRfc1123(String date) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("GMT"));
            Date d = f.parse(date);
            return d == null ? 0 : d.getTime();
        } catch (Throwable t) {
            return 0;
        }
    }
}
