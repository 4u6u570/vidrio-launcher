package com.augusto.vidrio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.GZIPInputStream;

/** Plain downloads with short timeouts. Call it off the main thread. */
final class Net {
    private static final String AGENT = "VidrioLauncher/1.1 (https://github.com/4u6u570/vidrio-launcher)";

    private Net() {}

    static byte[] get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        c.setRequestProperty("User-Agent", AGENT);
        c.setRequestProperty("Accept-Encoding", "gzip");
        try {
            if (c.getResponseCode() != 200) throw new IOException(url + " → " + c.getResponseCode());
            InputStream in = c.getInputStream();
            if ("gzip".equalsIgnoreCase(c.getContentEncoding())) in = new GZIPInputStream(in);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    static String text(String url) throws IOException {
        return new String(get(url), "UTF-8");
    }
}
