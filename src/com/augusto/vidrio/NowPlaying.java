package com.augusto.vidrio;

import android.animation.ObjectAnimator;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * What another app is playing: the album cover, a small equalizer that moves while it plays,
 * the song and the artist. It shows up only while something is playing or paused, and listens
 * only while the launcher is on screen.
 */
final class NowPlaying extends GlassView {
    /** Apps whose titles are songs, so a cover can be looked up for them. */
    private static final String[] MUSIC_APPS = {"com.spotify.tv.android", "com.spotify.music"};

    private final MediaSessionManager sessions;
    private final ComponentName listener;
    private MediaController controller;

    private final ImageView art;
    private final TextView title, artist;
    private final View[] bars = new View[4];
    private final ObjectAnimator[] dance = new ObjectAnimator[4];
    private boolean playing;

    private final LruCache<String, Bitmap> covers = new LruCache<>(8);
    private String coverKey = "";

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) {
            update();
        }

        @Override public void onMetadataChanged(MediaMetadata metadata) {
            update();
        }

        @Override public void onSessionDestroyed() {
            pick(null);
        }
    };
    private final MediaSessionManager.OnActiveSessionsChangedListener changed = this::pick;

    NowPlaying(Context context, float u) {
        super(context, u, 34 * u);
        sessions = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        listener = new ComponentName(context, MediaListener.class);
        setElevation(4 * u);
        setVisibility(GONE);
        // Selectable: OK opens the app that is playing.
        setFocusable(true);
        setClickable(true);
        setOnClickListener(v -> openApp());
        setOnFocusChangeListener((v, focused) -> {
            setLit(focused);
            animate().scaleX(focused ? 1.05f : 1f).scaleY(focused ? 1.05f : 1f)
                    .translationZ(focused ? 20 * u : 0).setDuration(170).start();
        });

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = px(20);
        column.setPadding(pad, pad, pad, px(22));
        addView(column);

        art = new ImageView(context);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 22 * NowPlaying.this.u);
            }
        });
        art.setClipToOutline(true);
        column.addView(art, new LinearLayout.LayoutParams(px(280), px(280)));

        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(px(280), -2);
        rp.topMargin = px(18);
        column.addView(row, rp);

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        title = text(27, "sans-serif-medium", 0xFFFFFFFF);
        artist = text(23, "sans-serif", 0xBFFFFFFF);
        texts.addView(title);
        texts.addView(artist);

        // The equalizer: four bars that only grow and shrink, which the GPU animates without
        // repainting the card.
        LinearLayout eq = new LinearLayout(context);
        eq.setGravity(Gravity.BOTTOM);
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-2, px(34));
        ep.leftMargin = px(14);
        row.addView(eq, ep);
        long[] speeds = {520, 380, 610, 450};
        for (int i = 0; i < bars.length; i++) {
            View bar = new View(context);
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(0xFF1ED760);
            shape.setCornerRadius(px(3));
            bar.setBackground(shape);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(px(6), px(34));
            if (i > 0) bp.leftMargin = px(4);
            eq.addView(bar, bp);
            bar.setPivotY(px(34));
            bar.setScaleY(0.25f);
            bars[i] = bar;
            dance[i] = ObjectAnimator.ofFloat(bar, View.SCALE_Y, 0.25f, 1f);
            dance[i].setDuration(speeds[i]);
            dance[i].setRepeatCount(ObjectAnimator.INFINITE);
            dance[i].setRepeatMode(ObjectAnimator.REVERSE);
        }
    }

    /**
     * Starts listening. Android refuses notification access on devices that call themselves
     * low on memory, as this TV stick does; there the card reads the system's media report
     * instead, every few seconds. That needs the DUMP permission, granted once with:
     * adb shell pm grant com.augusto.vidrio android.permission.DUMP
     * Without either, the card stays hidden.
     */
    void start() {
        try {
            sessions.addOnActiveSessionsChangedListener(changed, listener);
            pick(sessions.getActiveSessions(listener));
        } catch (SecurityException noAccess) {
            reporting = true;
            removeCallbacks(poll);
            poll.run();
        }
    }

    void stop() {
        removeCallbacks(poll);
        setDancing(false);
        try {
            sessions.removeOnActiveSessionsChangedListener(changed);
        } catch (SecurityException ignored) {
            // Never started.
        }
        if (controller != null) controller.unregisterCallback(callback);
        controller = null;
    }

    /** Follows whatever is playing; failing that, the first app with something paused. */
    private void pick(List<MediaController> list) {
        if (list == null) {
            try {
                list = sessions.getActiveSessions(listener);
            } catch (SecurityException e) {
                return;
            }
        }
        MediaController best = null;
        for (MediaController c : list) {
            PlaybackState s = c.getPlaybackState();
            if (c.getMetadata() == null || s == null) continue;
            if (s.getState() == PlaybackState.STATE_PLAYING) {
                best = c;
                break;
            }
            if (best == null && s.getState() == PlaybackState.STATE_PAUSED) best = c;
        }
        if (controller != null) controller.unregisterCallback(callback);
        controller = best;
        if (controller != null) controller.registerCallback(callback);
        update();
    }

    private void update() {
        MediaMetadata m = controller == null ? null : controller.getMetadata();
        PlaybackState s = controller == null ? null : controller.getPlaybackState();
        int state = s == null ? PlaybackState.STATE_NONE : s.getState();
        if (m == null) {
            show(null, "", "", state, null);
            return;
        }
        Bitmap cover = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (cover == null) cover = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (cover == null) cover = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        show(controller.getPackageName(),
                first(m, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
                first(m, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
                        MediaMetadata.METADATA_KEY_ALBUM),
                state, cover);
    }

    /** How long the last song stays on the card after its app stops reporting it. */
    private static final long LINGER_MS = 60L * 60 * 1000;
    private String lastPkg, lastName, lastBy;
    private long lastSeen;

    private void show(String pkg, String name, String by, int state, Bitmap cover) {
        boolean nowPlaying = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING;
        if (pkg == null || name.isEmpty() || !(nowPlaying || state == PlaybackState.STATE_PAUSED)) {
            // Spotify drops its session a while after the music stops, and the stick closes
            // apps to save memory. The last song stays, paused, for an hour all the same.
            if (lastPkg != null && System.currentTimeMillis() - lastSeen < LINGER_MS) {
                setDancing(false);
                setVisibility(VISIBLE);
            } else {
                setDancing(false);
                setVisibility(GONE);
            }
            return;
        }
        lastPkg = pkg;
        lastName = name;
        lastBy = by;
        lastSeen = System.currentTimeMillis();
        title.setText(name);
        artist.setText(by.isEmpty() ? appName(pkg) : by);

        String key = pkg + "|" + name + "|" + by;
        if (cover != null) {
            art.setImageBitmap(cover);
        } else if (covers.get(key) != null) {
            art.setImageBitmap(covers.get(key));
        } else if (!key.equals(coverKey)) {
            art.setImageDrawable(appIcon(pkg));
            if (isMusic(pkg)) lookUpCover(key, name, by);
        }
        coverKey = key;
        setDancing(nowPlaying);
        setVisibility(VISIBLE);
    }

    private void setDancing(boolean on) {
        if (on == playing) return;
        playing = on;
        for (int i = 0; i < bars.length; i++) {
            if (on) {
                if (dance[i].isPaused()) dance[i].resume();
                else dance[i].start();
            } else {
                dance[i].pause();
                bars[i].setScaleY(0.25f);
            }
        }
    }

    /** Opens the app on the card, the TV version when it has one. */
    private void openApp() {
        if (lastPkg == null) return;
        PackageManager pm = getContext().getPackageManager();
        android.content.Intent open = pm.getLeanbackLaunchIntentForPackage(lastPkg);
        if (open == null) open = pm.getLaunchIntentForPackage(lastPkg);
        if (open == null) return;
        try {
            getContext().startActivity(open.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (android.content.ActivityNotFoundException ignored) {
            // The app went away.
        }
    }

    // ---- Covers ----

    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();

    private static boolean isMusic(String pkg) {
        for (String m : MUSIC_APPS) if (m.equals(pkg)) return true;
        return false;
    }

    /** The song's cover from the iTunes catalog: free, no key, one small picture per song. */
    private void lookUpCover(String key, String song, String by) {
        WORK.execute(() -> {
            try {
                JSONArray results = new JSONObject(Net.text("https://itunes.apple.com/search?entity=song&limit=1"
                        + "&country=AR&term=" + Uri.encode(by + " " + song))).getJSONArray("results");
                if (results.length() == 0) return;
                String url = results.getJSONObject(0).getString("artworkUrl100").replace("100x100bb", "400x400bb");
                byte[] jpg = Net.get(url);
                Bitmap b = BitmapFactory.decodeByteArray(jpg, 0, jpg.length);
                if (b == null) return;
                post(() -> {
                    covers.put(key, b);
                    if (key.equals(coverKey)) art.setImageBitmap(b);
                });
            } catch (Exception e) {
                // No cover: the app's icon stays.
                android.util.Log.w("Vidrio", "Sin portada para " + key, e);
            }
        });
    }

    // ---- Reading the system's media report, where notification access is refused ----

    private boolean reporting;
    private final Runnable poll = this::readReport;

    private void readReport() {
        WORK.execute(() -> {
            String[] found = parseReport();
            post(() -> {
                if (found == null) show(null, "", "", PlaybackState.STATE_NONE, null);
                else show(found[0], found[1], found[2], Integer.parseInt(found[3]), null);
                removeCallbacks(poll);
                if (isAttachedToWindow() && reporting) postDelayed(poll, 4_000);
            });
        });
    }

    /**
     * The session that is playing, or failing that one that is paused, from "dumpsys
     * media_session": package, title, artist and state. Null when there is none or no access.
     */
    private static String[] parseReport() {
        String pkg = null, state = null;
        String[] paused = null;
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"dumpsys", "media_session"});
            java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            for (String line; (line = in.readLine()) != null; ) {
                line = line.trim();
                if (line.startsWith("package=")) {
                    pkg = line.substring(8);
                    state = null;
                } else if (line.startsWith("state=PlaybackState {state=")) {
                    int from = 27, to = line.indexOf(',', from);
                    state = to > from ? line.substring(from, to) : null;
                } else if (line.startsWith("metadata:") && pkg != null && state != null) {
                    int at = line.indexOf("description=");
                    if (at < 0) continue;
                    String d = line.substring(at + 12);
                    if (d.equals("null") || d.isEmpty()) continue;
                    // The description is "title, subtitle, description".
                    String[] parts = d.split(", ");
                    String[] found = {pkg, parts[0], parts.length > 1 && !parts[1].equals("null") ? parts[1] : "", state};
                    if (state.equals(String.valueOf(PlaybackState.STATE_PLAYING))) {
                        p.destroy();
                        return found;
                    }
                    if (paused == null && state.equals(String.valueOf(PlaybackState.STATE_PAUSED))) paused = found;
                }
            }
            p.waitFor();
        } catch (Exception e) {
            return null;
        }
        return paused;
    }

    // ---- Small helpers ----

    private static String first(MediaMetadata m, String... keys) {
        for (String k : keys) {
            CharSequence v = m.getText(k);
            if (!TextUtils.isEmpty(v)) return v.toString();
        }
        return "";
    }

    private String appName(String pkg) {
        PackageManager pm = getContext().getPackageManager();
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private android.graphics.drawable.Drawable appIcon(String pkg) {
        try {
            return getContext().getPackageManager().getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private TextView text(float size, String family, int color) {
        TextView t = new TextView(getContext());
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * u);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setTextColor(color);
        t.setSingleLine();
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setShadowLayer(8 * u, 0, u, 0x59000000);
        return t;
    }

    private int px(float v) {
        return Math.round(v * u);
    }
}
