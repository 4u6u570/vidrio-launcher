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
 * A new wallpaper every so often, from Wikimedia Commons: free, no key, and every photo there
 * carries a license that allows this. Two themes: astronomy, from Commons' featured pictures of
 * galaxies, auroras, the Milky Way, the Moon and the Solar System; and Salta, from its quality
 * images. Both are sharp and large. The list of candidates is fetched once a week and a photo
 * once per period, only while the launcher is on screen. All of it runs off the main thread.
 */
final class Wallpapers {
    static final int ASTRONOMY = 0, SALTA = 1;
    /** Featured pictures of the sky; their subcategories hold the best of Commons' astronomy. */
    private static final String[] SKY = {
            "Featured_pictures_of_galaxies", "Featured_pictures_of_aurora", "Featured_pictures_of_the_Milky_Way",
            "Featured_pictures_of_the_Moon", "Featured_pictures_of_Solar_System", "Featured_pictures_of_the_Sun"};
    /** Places in Salta that have quality photos on Commons. */
    private static final String[] PLACES = {
            "Salta", "Cafayate", "Cachi", "\"Quebrada de las Conchas\"", "\"Los Cardones\"",
            "Iruya", "\"Valles Calchaquíes\"", "\"San Antonio de los Cobres\""};
    private static final String API = "https://commons.wikimedia.org/w/api.php?format=json&action=query";
    private static final long WEEK = 7L * 24 * 60 * 60 * 1000;
    /**
     * Titles that give away a chart, a labelled sky, a diagram or a collage rather than a
     * photo: they look poor full screen. ESO's "picture of the week" (iotw) are often labelled.
     */
    private static final java.util.regex.Pattern NOT_A_PHOTO = java.util.regex.Pattern.compile(
            "(?i)annotat|label|iotw|chart|\\bmap\\b|diagram|poster|sequence|path|transit|comparison|collage"
                    + "|anim|illustration|artist|render|360|panorama|mosaic of|scale");
    /** Raised when the rules above change, so lists and photos picked by older rules go. */
    private static final int RULES = 2;

    static final class Photo {
        final Bitmap bitmap;
        /** "Quebrada de las Conchas · Foto: Jane Doe, CC BY-SA 4.0" */
        final String credit;

        Photo(Bitmap bitmap, String credit) {
            this.bitmap = bitmap;
            this.credit = credit;
        }
    }

    private final File image, dir;
    private final SharedPreferences prefs;

    Wallpapers(Context context) {
        image = new File(context.getFilesDir(), "fondo.jpg");
        dir = context.getFilesDir();
        new File(dir, "fondos.txt").delete(); // The list from before there were themes.
        for (int r = 0; r < RULES; r++) {
            new File(dir, "fondos-" + ASTRONOMY + (r == 0 ? "" : "-r" + r) + ".txt").delete();
            new File(dir, "fondos-" + SALTA + (r == 0 ? "" : "-r" + r) + ".txt").delete();
        }
        prefs = context.getSharedPreferences("fondos", Context.MODE_PRIVATE);
    }

    /** The photo already on disk, if it belongs to this theme. Quick enough for startup. */
    Photo cached(int theme) {
        if (!image.exists() || prefs.getInt("tema", -1) != theme || prefs.getInt("reglas", 0) != RULES) return null;
        Bitmap b = BitmapFactory.decodeFile(image.getPath());
        return b == null ? null : new Photo(b, prefs.getString("credito", ""));
    }

    /** The photo for these twenty minutes, downloading a new one when they change; null if nothing changed. */
    Photo today(boolean force, int periodMinutes, int theme) throws Exception {
        // The key changes once per period: every twenty minutes, every hour or every day.
        java.util.Calendar now = java.util.Calendar.getInstance();
        int minuteOfDay = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE);
        String day = new SimpleDateFormat("yyyyMMdd", Locale.US).format(now.getTime())
                + "-" + minuteOfDay / periodMinutes;
        boolean sameTheme = prefs.getInt("tema", -1) == theme && prefs.getInt("reglas", 0) == RULES;
        if (!force && sameTheme && day.equals(prefs.getString("dia", "")) && image.exists()) return null;

        List<String> titles = candidates(theme);
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
        String artist = plain(field(meta, "Artist"), 40);
        String license = field(meta, "LicenseShortName");
        String name = plain(field(meta, "ObjectName"), 60);
        String credit = (name.isEmpty() ? placeOf(title) : name) + (artist.isEmpty() ? "" : " · Foto: " + artist)
                + (license.isEmpty() ? "" : ", " + license);
        prefs.edit().putString("dia", day).putInt("tema", theme).putInt("reglas", RULES).putString("titulo", title)
                .putString("credito", credit).apply();
        return new Photo(b, credit);
    }

    /** Wide photos of the theme, refreshed once a week. */
    private List<String> candidates(int theme) throws Exception {
        File list = new File(dir, "fondos-" + theme + "-r" + RULES + ".txt");
        List<String> titles = new ArrayList<>();
        if (list.exists() && System.currentTimeMillis() - list.lastModified() < WEEK) {
            try (java.io.BufferedReader in = new java.io.BufferedReader(new java.io.FileReader(list))) {
                for (String t; (t = in.readLine()) != null; ) if (!t.isEmpty()) titles.add(t);
            }
            if (!titles.isEmpty()) return titles;
        }
        String[] queries = theme == SALTA ? PLACES : SKY;
        for (String q : queries) {
            JSONObject r = new JSONObject(Net.text(API + "&prop=imageinfo&iiprop=size%7Cmime" + (theme == SALTA
                    ? "&generator=search&gsrnamespace=6&gsrlimit=100&gsrsearch="
                            + Uri.encode("intitle:" + q + " incategory:Quality_images")
                    : "&generator=categorymembers&gcmtype=file&gcmlimit=500&gcmtitle=" + Uri.encode("Category:" + q))));
            JSONObject pages = r.optJSONObject("query") == null ? null : r.getJSONObject("query").optJSONObject("pages");
            if (pages == null) continue;
            for (Iterator<String> it = pages.keys(); it.hasNext(); ) {
                JSONObject p = pages.getJSONObject(it.next());
                if (p.optJSONArray("imageinfo") == null) continue;
                JSONObject size = p.getJSONArray("imageinfo").getJSONObject(0);
                int w = size.getInt("width"), h = size.getInt("height");
                String title = p.getString("title");
                // Big, close to the screen's shape, a real photo: nothing gets upscaled or squashed.
                boolean sharp = w >= 2560 && h >= 1300;
                boolean shape = w >= h * 1.45f && w <= h * 2.2f;
                boolean photo = "image/jpeg".equals(size.optString("mime")) && !NOT_A_PHOTO.matcher(title).find();
                if (sharp && shape && photo) titles.add(title);
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

    /** Commons sends names as HTML, often a link. */
    private static String plain(String html, int max) {
        String s = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().trim();
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }

    /** "File:Quebrada de las Conchas 03.jpg" → "Quebrada de las Conchas" */
    private static String placeOf(String title) {
        return title.replaceFirst("^File:", "").replaceFirst("\\.[A-Za-z]+$", "")
                .replaceFirst("\\s*\\d+$", "").trim();
    }
}
