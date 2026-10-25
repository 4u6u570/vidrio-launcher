package com.augusto.vidrio;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * What another app is playing, with its cover and play/pause, previous and next. It shows up
 * only while something is playing or paused, and listens only while the launcher is on screen.
 */
final class NowPlaying extends GlassView {
    private final MediaSessionManager sessions;
    private final ComponentName listener;
    private MediaController controller;

    private final ImageView art;
    private final TextView app, title, artist;
    private final Icon playPause;

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

        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int) (22 * u);
        row.setPadding(pad, pad, (int) (30 * u), pad);
        addView(row);

        art = new ImageView(context);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 20 * NowPlaying.this.u);
            }
        });
        art.setClipToOutline(true);
        int size = (int) (156 * u);
        row.addView(art, new LinearLayout.LayoutParams(size, size));

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1);
        cp.leftMargin = (int) (26 * u);
        row.addView(column, cp);

        app = text(20, "sans-serif-medium", 0x99FFFFFF);
        title = text(32, "sans-serif-medium", 0xFFFFFFFF);
        artist = text(24, "sans-serif", 0xCCFFFFFF);
        column.addView(app);
        column.addView(title);
        column.addView(artist);

        LinearLayout controls = new LinearLayout(context);
        controls.setClipChildren(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = (int) (14 * u);
        column.addView(controls, lp);
        controls.addView(button(new Icon(context, Icon.PREVIOUS), () -> {
            if (controller != null) controls().skipToPrevious();
            else mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        }));
        playPause = new Icon(context, Icon.PAUSE);
        controls.addView(button(playPause, () -> {
            if (controller == null) {
                mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                return;
            }
            PlaybackState s = controller.getPlaybackState();
            if (s != null && s.getState() == PlaybackState.STATE_PLAYING) controls().pause();
            else controls().play();
        }));
        controls.addView(button(new Icon(context, Icon.NEXT), () -> {
            if (controller != null) controls().skipToNext();
            else mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT);
        }));
    }

    /**
     * Starts listening. Android refuses notification access on devices that call themselves
     * low on memory, as this TV stick does; there the card reads the system's media report
     * instead, every few seconds, and drives playback with media keys. That needs the DUMP
     * permission, granted once with: adb shell pm grant com.augusto.vidrio android.permission.DUMP
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

    private void show(String pkg, String name, String by, int state, Bitmap cover) {
        boolean playing = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING;
        if (pkg == null || name.isEmpty() || !(playing || state == PlaybackState.STATE_PAUSED)) {
            setVisibility(GONE);
            return;
        }
        title.setText(name);
        artist.setText(by);
        app.setText(appName(pkg).toUpperCase());
        if (cover != null) art.setImageBitmap(cover);
        else art.setImageDrawable(appIcon(pkg));
        playPause.setShape(playing ? Icon.PAUSE : Icon.PLAY);
        setVisibility(VISIBLE);
    }

    // ---- Reading the system's media report, where notification access is refused ----

    private boolean reporting;
    private final Runnable poll = this::readReport;
    private static final java.util.concurrent.ExecutorService REPORTS =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private void readReport() {
        REPORTS.execute(() -> {
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

    /** Media keys go to whichever app is playing, and need no permission. */
    private void mediaKey(int code) {
        android.media.AudioManager audio = (android.media.AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
        removeCallbacks(poll);
        if (reporting) postDelayed(poll, 700);
    }

    private MediaController.TransportControls controls() {
        return controller.getTransportControls();
    }

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

    /** A round glass button around an icon. */
    private View button(Icon icon, Runnable action) {
        GlassView g = new GlassView(getContext(), u, 32 * u);
        g.setFocusable(true);
        g.setClickable(true);
        g.addView(icon, new FrameLayout.LayoutParams(-1, -1));
        g.setOnClickListener(v -> action.run());
        g.setOnFocusChangeListener((v, focused) -> {
            g.setLit(focused);
            g.animate().scaleX(focused ? 1.12f : 1f).scaleY(focused ? 1.12f : 1f).setDuration(140).start();
        });
        int size = (int) (64 * u);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.rightMargin = (int) (16 * u);
        g.setLayoutParams(lp);
        return g;
    }

    /** Previous, play, pause and next, drawn rather than taken from a font. */
    static final class Icon extends View {
        static final int PREVIOUS = 0, PLAY = 1, PAUSE = 2, NEXT = 3;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private int shape;

        Icon(Context context, int shape) {
            super(context);
            this.shape = shape;
            p.setColor(0xF2FFFFFF);
        }

        void setShape(int shape) {
            if (this.shape == shape) return;
            this.shape = shape;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float s = Math.min(getWidth(), getHeight()) * 0.34f;
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            path.reset();
            switch (shape) {
                case PLAY:
                    triangle(cx - s * 0.4f, cy, s, true);
                    break;
                case PAUSE:
                    c.drawRoundRect(cx - s * 0.55f, cy - s * 0.6f, cx - s * 0.15f, cy + s * 0.6f, s * 0.1f, s * 0.1f, p);
                    c.drawRoundRect(cx + s * 0.15f, cy - s * 0.6f, cx + s * 0.55f, cy + s * 0.6f, s * 0.1f, s * 0.1f, p);
                    return;
                case NEXT:
                    triangle(cx - s * 0.6f, cy, s * 0.8f, true);
                    c.drawRect(cx + s * 0.3f, cy - s * 0.5f, cx + s * 0.48f, cy + s * 0.5f, p);
                    break;
                default:
                    triangle(cx + s * 0.6f, cy, s * 0.8f, false);
                    c.drawRect(cx - s * 0.48f, cy - s * 0.5f, cx - s * 0.3f, cy + s * 0.5f, p);
            }
            c.drawPath(path, p);
        }

        /** A triangle pointing right (or left) whose flat side sits at x. */
        private void triangle(float x, float cy, float s, boolean right) {
            float d = right ? s * 1.05f : -s * 1.05f;
            path.moveTo(x, cy - s * 0.6f);
            path.lineTo(x + d, cy);
            path.lineTo(x, cy + s * 0.6f);
            path.close();
        }
    }
}
