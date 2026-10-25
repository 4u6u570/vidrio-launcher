package com.augusto.vidrio;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.text.Html;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * A photo of Salta every twenty minutes, from Wikimedia Commons: free, no key, and every photo there
 * carries a license that allows this. Only "quality images" are used, which are sharp and
 * large. The list of candidates is fetched once a week and a photo every twenty minutes, so the
 * launcher downloads three small pictures an hour, only while it is on screen. All of it runs off the main thread.
 */
final class Wallpapers {
    /** Places in Salta that have quality photos on Commons. */
    private static final String[] PLACES = {
            "Salta", "Cafayate", "Cachi", "\"Quebrada de las Conchas\"", "\"Los Cardones\"",
            "Iruya", "\"Valles Calchaquíes\"", "\"San Antonio de los Cobres\""};
    private static final String API = "https://commons.wikimedia.org/w/api.php?format=json&action=query";
    private static final long WEEK = 7L * 24 * 60 * 60 * 1000;

    static final class Photo {
        final Bitmap bitmap;
        /** "Quebrada de las Conchas · Foto: Jane Doe, CC BY-SA 4.0" */
        final String credit;

        Photo(Bitmap bitmap, String credit) {
            this.bitmap = bitmap;
            this.credit = credit;
        }
    }

    private final File image, list;
    private final SharedPreferences prefs;

    Wallpapers(Context context) {
        image = new File(context.getFilesDir(), "fondo.jpg");
        list = new File(context.getFilesDir(), "fondos.txt");
        prefs = context.getSharedPreferences("fondos", Context.MODE_PRIVATE);
    }

    /** The photo already on disk, if any. Quick enough for startup. */
    Photo cached() {
        if (!image.exists()) return null;
        Bitmap b = BitmapFactory.decodeFile(image.getPath());
        return b == null ? null : new Photo(b, prefs.getString("credito", ""));
    }

    /** The photo for these twenty minutes, downloading a new one when they change; null if nothing changed. */
    Photo today(boolean force) throws Exception {
        // One photo every twenty minutes: the key changes three times an hour.
        java.util.Calendar now = java.util.Calendar.getInstance();
        String day = new SimpleDateFormat("yyyyMMddHH", Locale.US).format(now.getTime())
                + now.get(java.util.Calendar.MINUTE) / 20;
        if (!force && day.equals(prefs.getString("dia", "")) && image.exists()) return null;

        List<String> titles = candidates();
        if (titles.isEmpty()) return null;
        String current = prefs.getString("titulo", "");
        String title;
        Random random = new Random();
        do title = titles.get(random.nextInt(titles.size()));
        while (titles.size() > 1 && title.equals(current));

        JSONObject page = firstPage(new JSONObject(Net.text(API
                + "&prop=imageinfo&iiprop=url%7Cextmetadata&iiurlwidth=1920&titles=" + Uri.encode(title))));
        JSONObject info = page.getJSONArray("imageinfo").getJSONObject(0);
        byte[] jpg = Net.get(info.getString("thumburl"));
        Bitmap b = BitmapFactory.decodeByteArray(jpg, 0, jpg.length);
        if (b == null) return null;
        try (FileOutputStream out = new FileOutputStream(image)) {
            out.write(jpg);
        }

        JSONObject meta = info.optJSONObject("extmetadata");
        String artist = plain(field(meta, "Artist"));
        String license = field(meta, "LicenseShortName");
        String credit = placeOf(title) + (artist.isEmpty() ? "" : " · Foto: " + artist)
                + (license.isEmpty() ? "" : ", " + license);
        prefs.edit().putString("dia", day).putString("titulo", title).putString("credito", credit).apply();
        return new Photo(b, credit);
    }

    /** Wide quality photos of the places above, refreshed once a week. */
    private List<String> candidates() throws Exception {
        List<String> titles = new ArrayList<>();
        if (list.exists() && System.currentTimeMillis() - list.lastModified() < WEEK) {
            try (java.io.BufferedReader in = new java.io.BufferedReader(new java.io.FileReader(list))) {
                for (String t; (t = in.readLine()) != null; ) if (!t.isEmpty()) titles.add(t);
            }
            if (!titles.isEmpty()) return titles;
        }
        for (String place : PLACES) {
            JSONObject r = new JSONObject(Net.text(API
                    + "&generator=search&gsrnamespace=6&gsrlimit=100&prop=imageinfo&iiprop=size"
                    + "&gsrsearch=" + Uri.encode("intitle:" + place + " incategory:Quality_images")));
            JSONObject pages = r.optJSONObject("query") == null ? null : r.getJSONObject("query").optJSONObject("pages");
            if (pages == null) continue;
            for (Iterator<String> it = pages.keys(); it.hasNext(); ) {
                JSONObject p = pages.getJSONObject(it.next());
                JSONObject size = p.getJSONArray("imageinfo").getJSONObject(0);
                int w = size.getInt("width"), h = size.getInt("height");
                if (w >= 1920 && w > h * 1.3) titles.add(p.getString("title"));
            }
        }
        if (!titles.isEmpty()) {
            try (FileOutputStream out = new FileOutputStream(list)) {
                out.write(String.join("\n", titles).getBytes("UTF-8"));
            }
        }
        return titles;
    }

    private static JSONObject firstPage(JSONObject r) throws Exception {
        JSONObject pages = r.getJSONObject("query").getJSONObject("pages");
        return pages.getJSONObject(pages.keys().next());
    }

    private static String field(JSONObject meta, String name) {
        JSONObject f = meta == null ? null : meta.optJSONObject(name);
        return f == null ? "" : f.optString("value", "");
    }

    /** Commons sends the author as HTML, often a link. */
    private static String plain(String html) {
        String s = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().trim();
        return s.length() > 40 ? s.substring(0, 39) + "…" : s;
    }

    /** "File:Quebrada de las Conchas 03.jpg" → "Quebrada de las Conchas" */
    private static String placeOf(String title) {
        return title.replaceFirst("^File:", "").replaceFirst("\\.[A-Za-z]+$", "")
                .replaceFirst("\\s*\\d+$", "").trim();
    }
}
