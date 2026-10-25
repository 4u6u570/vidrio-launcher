package com.augusto.vidrio;

import android.animation.LayoutTransition;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home: the clock on top, an empty middle kept free for widgets, and one row of favorites along
 * the bottom that starts with the way into every installed app. New apps join the row by themselves.
 */
public class MainActivity extends Activity {
    private static final Locale ES = new Locale("es", "AR");
    private static final String FAVORITES = "favoritos";
    /** Picked as the first favorites when they are installed. */
    private static final List<String> SUGGESTED = Arrays.asList(
            "org.smarttube.stable", "com.stremio.one", "com.google.android.youtube.tv",
            "com.netflix.ninja", "tunein.player", "com.android.vending");
    private static final int COLUMNS = 5, GAP = 36, SIDE = 96;

    private float u;
    private int tileW, tileH;
    private SharedPreferences prefs;
    private final Map<String, AppTile.App> apps = new LinkedHashMap<>();

    private FrameLayout root, home;
    private LinearLayout favoritesRow;
    private TextView time, date;
    /** The screen with every app; null while closed. */
    private FrameLayout appsLayer;
    private LinearLayout grid;
    private FrameLayout sheet;
    private ViewGroup sheetBlocks;
    private String sheetPackage;
    /** Where focus goes back to when a panel that isn't an app's menu closes. */
    private View sheetReturn;

    private final BroadcastReceiver clockReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            updateClock();
            // Checks each minute whether the period for a new photo has run out; cheap.
            if (visible) refreshWallpaper();
        }
    };
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            loadApps();
            String pkg = i.getData() == null ? null : i.getData().getSchemeSpecificPart();
            boolean fresh = Intent.ACTION_PACKAGE_ADDED.equals(i.getAction())
                    && !i.getBooleanExtra(Intent.EXTRA_REPLACING, false);
            if (fresh && apps.containsKey(pkg) && config.on(Config.AUTO_FAVORITES)) {
                List<String> favs = favorites();
                if (!favs.contains(pkg)) {
                    favs.add(pkg);
                    saveFavorites(favs);
                }
            }
            buildFavorites();
            if (appsLayer != null) buildGrid();
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        u = getResources().getDisplayMetrics().widthPixels / 1920f;
        tileW = (px(1920 - 2 * SIDE) - (COLUMNS - 1) * px(GAP)) / COLUMNS;
        tileH = tileW * 9 / 16;
        prefs = getSharedPreferences("vidrio", MODE_PRIVATE);
        config = new Config(this);
        Matches.favoriteTeam = Config.TEAM_NAMES[config.get(Config.TEAM)];
        wallpapers = new Wallpapers(this);
        Wallpapers.Photo photo = GlassView.backdrop == null && photos() ? wallpapers.cached(theme()) : null;
        if (photo != null) {
            GlassView.backdrop = new Backdrop(photo.bitmap, screenW(), screenH());
            photo.bitmap.recycle();
        }
        if (GlassView.backdrop == null) GlassView.backdrop = new Backdrop();

        root = new FrameLayout(this);
        root.addView(wallpaperView = new View(this) {
            final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
            final Rect dst = new Rect();
            @Override protected void onDraw(Canvas c) {
                dst.set(0, 0, getWidth(), getHeight());
                c.drawBitmap(GlassView.backdrop.wallpaper, null, dst, p);
            }
        }, match());

        home = new FrameLayout(this);
        home.setClipChildren(false);
        root.addView(home, match());

        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        hp.leftMargin = hp.rightMargin = px(SIDE);
        hp.topMargin = px(56);
        home.addView(header(), hp);

        credit = text(20, "sans-serif", 0xA6FFFFFF);
        credit.setGravity(Gravity.END);
        credit.setSingleLine();
        credit.setEllipsize(android.text.TextUtils.TruncateAt.END);
        credit.setText(prefsCredit());
        credit.setVisibility(photos() && config.on(Config.CREDIT) ? View.VISIBLE : View.GONE);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(px(1000), -2, Gravity.TOP | Gravity.END);
        cp.rightMargin = px(SIDE);
        cp.topMargin = px(178);
        home.addView(credit, cp);

        // The middle of the screen is for widgets: what is playing goes on the left.
        nowPlaying = new NowPlaying(this, u);
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        np.leftMargin = px(SIDE);
        np.topMargin = px(250);
        home.addView(nowPlaying, np);

        // The row slides on its own instead of scrolling, so the focused card always keeps the
        // side margin: a scroll view stops with the last card flush against the screen edge.
        FrameLayout strip = new FrameLayout(this);
        strip.setClipChildren(false);
        strip.setClipToPadding(false);
        // Room for a focused card to grow and show its name without being cut off.
        strip.setPadding(px(SIDE), px(40), px(SIDE), px(72));
        favoritesRow = new LinearLayout(this);
        favoritesRow.setClipChildren(false);
        strip.addView(favoritesRow, new FrameLayout.LayoutParams(-2, tileH));
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        fp.bottomMargin = px(24);
        home.addView(strip, fp);
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((was, now) -> {
            if (now != null && now.getParent() == favoritesRow) slideTo(now);
        });

        setContentView(root);
        loadApps();
        buildFavorites();
        focusFirst();

        registerReceiver(packageReceiver, packageFilter());
        IntentFilter clock = new IntentFilter(Intent.ACTION_TIME_TICK);
        clock.addAction(Intent.ACTION_TIME_CHANGED);
        clock.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(clockReceiver, clock);
    }

    @Override protected void onResume() {
        super.onResume();
        visible = true;
        updateClock();
        refreshWallpaper();
        refreshWeather();
        nowPlaying.lingerMs = Config.LINGER_MINUTES[config.get(Config.LINGER)] * 60_000L;
        if (config.on(Config.MUSIC)) nowPlaying.start();
        else nowPlaying.setVisibility(View.GONE);
        main.removeCallbacks(pollMatches);
        refreshMatches();
    }

    /** Nothing is fetched while an app is on screen: the widgets only work while seen. */
    @Override protected void onPause() {
        visible = false;
        main.removeCallbacks(pollMatches);
        main.removeCallbacks(rotateLive);
        pulse.pause();
        nowPlaying.stop();
        super.onPause();
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        unregisterReceiver(packageReceiver);
        unregisterReceiver(clockReceiver);
        super.onDestroy();
    }

    // ---- Widgets ----

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Bitmap> logos = new ConcurrentHashMap<>();
    private final Runnable pollMatches = this::refreshMatches;
    private Wallpapers wallpapers;
    private Config config;
    private View wallpaperView;
    private TextView credit;
    private NowPlaying nowPlaying;
    private boolean visible, fetchingMatches;
    private long weatherAt;
    private Weather forecast;
    /** The live-match pill in the top bar: only there while something is being played. */
    private GlassView live;
    private View liveContent;
    private Matches.Match shownLive;
    /** The open match panel, refreshed with every poll; null while closed. */
    private LinearLayout matchBody;
    private String matchId;
    private ImageView liveHome, liveAway;
    private TextView liveClock, liveScore, weather;
    private ValueAnimator pulse;
    private List<Matches.Match> liveMatches = new ArrayList<>();
    private int liveIndex;
    /** With more than one match being played, the pill takes turns. */
    private final Runnable rotateLive = () -> {
        liveIndex++;
        showLive();
    };

    /**
     * Asks again in a minute while something is being played; otherwise at the next kickoff,
     * and never later than in fifteen minutes.
     */
    private void refreshMatches() {
        if (!config.on(Config.FOOTBALL)) {
            liveMatches = new ArrayList<>();
            showLive();
            return;
        }
        if (fetchingMatches) return;
        fetchingMatches = true;
        worker.execute(() -> {
            Matches.Board board = Matches.fetch();
            for (Matches.Match m : board.matches) {
                for (String url : new String[]{m.home.logo, m.away.logo}) {
                    if (url == null || logos.containsKey(url)) continue;
                    try {
                        byte[] png = Net.get(url);
                        Bitmap b = BitmapFactory.decodeByteArray(png, 0, png.length);
                        if (b != null) logos.put(url, b);
                    } catch (Exception ignored) {
                        // A team without its crest still shows its name.
                    }
                }
            }
            main.post(() -> {
                fetchingMatches = false;
                if (isDestroyed()) return;
                // Only the most important tier is shown: Boca alone when it plays; otherwise the
                // Argentine teams; otherwise everything else.
                int best = 3;
                for (Matches.Match m : board.matches) if (m.live) best = Math.min(best, m.priority);
                liveMatches = new ArrayList<>();
                for (Matches.Match m : board.matches) if (m.live && m.priority == best) liveMatches.add(m);
                showLive();
                if (matchBody != null) {
                    for (Matches.Match m : board.matches) if (m.id.equals(matchId)) fillMatch(m);
                }
                long minute = 60_000, quarter = 15 * minute;
                long delay = board.anyLive() ? minute : quarter;
                if (!board.anyLive() && board.nextStart > 0) {
                    delay = Math.max(minute, Math.min(quarter, board.nextStart - System.currentTimeMillis()));
                }
                main.removeCallbacks(pollMatches);
                if (visible) main.postDelayed(pollMatches, delay);
            });
        });
    }

    private void showLive() {
        main.removeCallbacks(rotateLive);
        if (liveMatches.isEmpty()) {
            live.setVisibility(View.GONE);
            pulse.pause();
            return;
        }
        Matches.Match m = liveMatches.get(liveIndex % liveMatches.size());
        if (live.getVisibility() == View.VISIBLE) {
            // Changing matches: the old one fades out, the new one fades in.
            liveContent.animate().alpha(0f).setDuration(220).withEndAction(() -> {
                fillLive(m);
                liveContent.animate().alpha(1f).setDuration(320).start();
            }).start();
        } else {
            fillLive(m);
            liveContent.setAlpha(1f);
        }
        live.setVisibility(View.VISIBLE);
        if (pulse.isPaused()) pulse.resume();
        else if (!pulse.isStarted()) pulse.start();
        if (liveMatches.size() > 1 && visible) main.postDelayed(rotateLive, 8_000);
    }

    private void fillLive(Matches.Match m) {
        shownLive = m;
        liveClock.setText(m.status);
        liveScore.setText(m.home.abbr + "  " + m.home.goals + " – " + m.away.goals + "  " + m.away.abbr);
        liveHome.setImageBitmap(m.home.logo == null ? null : logos.get(m.home.logo));
        liveAway.setImageBitmap(m.away.logo == null ? null : logos.get(m.away.logo));
    }

    /** Every half hour at most, and only while the launcher is on screen. */
    private void refreshWeather() {
        View pill = (View) weather.getParent();
        if (!config.on(Config.WEATHER)) {
            pill.setVisibility(View.GONE);
            return;
        }
        if (System.currentTimeMillis() - weatherAt < 30 * 60_000) return;
        double[] at = Config.CITY_COORDS[config.get(Config.CITY)];
        worker.execute(() -> {
            Weather w;
            try {
                w = Weather.fetch(at[0], at[1]);
            } catch (Exception e) {
                return;
            }
            main.post(() -> {
                weatherAt = System.currentTimeMillis();
                forecast = w;
                Weather.Day today = w.days[0];
                weather.setText(w.icon + "  " + w.now + "°   " + today.max + "° / " + today.min + "°");
                ((View) weather.getParent()).setVisibility(View.VISIBLE);
            });
        });
    }

    private boolean photos() {
        return config.get(Config.WALLPAPER) != Config.WALLPAPER.choices.length - 1;
    }

    /** The photo theme chosen, in Wallpapers' numbering. */
    private int theme() {
        return config.get(Config.WALLPAPER);
    }

    /** A new photo of Salta when its period runs out; the glass follows the wallpaper. */
    private void refreshWallpaper() {
        refreshWallpaper(false);
    }

    private void refreshWallpaper(boolean force) {
        if (!photos()) return;
        int period = Config.PERIOD_MINUTES[config.get(Config.PERIOD)];
        int theme = theme();
        worker.execute(() -> {
            Wallpapers.Photo photo;
            try {
                photo = wallpapers.today(force, period, theme);
            } catch (Exception e) {
                return;
            }
            if (photo == null) return;
            Backdrop backdrop = new Backdrop(photo.bitmap, screenW(), screenH());
            photo.bitmap.recycle();
            main.post(() -> {
                if (isDestroyed() || !photos() || theme() != theme) return;
                swapBackdrop(backdrop);
                credit.setText(photo.credit);
            });
        });
    }

    /** The old wallpaper stays on top for a moment and fades away over the new one. */
    private void swapBackdrop(Backdrop backdrop) {
        android.graphics.Bitmap old = GlassView.backdrop.wallpaper;
        View fading = new View(this) {
            final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
            final Rect dst = new Rect();
            @Override protected void onDraw(Canvas c) {
                dst.set(0, 0, getWidth(), getHeight());
                c.drawBitmap(old, null, dst, p);
            }
        };
        root.addView(fading, 1, match());
        GlassView.backdrop = backdrop;
        wallpaperView.invalidate();
        GlassView.refresh(root);
        fading.animate().alpha(0f).setDuration(1400).withEndAction(() -> root.removeView(fading)).start();
    }

    private String prefsCredit() {
        return getSharedPreferences("fondos", MODE_PRIVATE).getString("credito", "");
    }

    private int screenW() {
        return getResources().getDisplayMetrics().widthPixels;
    }

    private int screenH() {
        return getResources().getDisplayMetrics().heightPixels;
    }

    /** Pressing Home while already home goes back to the start of the row. */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        closeSheet();
        closeApps();
        favoritesRow.setTranslationX(0);
        GlassView.refresh(favoritesRow);
        focusFirst();
    }

    @Override public void onBackPressed() {
        if (sheet != null) closeSheet();
        else if (appsLayer != null) closeApps();
    }

    private boolean okDown, okHeld;

    /**
     * OK on a card opens the app on release, or the app's menu when held. Android would fire
     * the click on its own before noticing the hold, so OK on a card is handled here.
     */
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
        if (!ok) return super.dispatchKeyEvent(e);
        View focus = getCurrentFocus();
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            okDown = sheet == null && focus instanceof AppTile;
            okHeld = false;
            if (okDown) return true;
        }
        if (!okDown) return super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_DOWN && !okHeld && focus instanceof AppTile) {
            okHeld = true;
            openSheet((AppTile) focus);
        } else if (e.getAction() == KeyEvent.ACTION_UP) {
            okDown = false;
            if (!okHeld && focus != null) focus.performClick();
        }
        return true;
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_MENU && sheet == null) {
            View f = getCurrentFocus();
            if (f instanceof AppTile) {
                openSheet((AppTile) f);
                return true;
            }
        }
        return super.onKeyDown(code, e);
    }

    // ---- Header ----

    private View header() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClipChildren(false);
        // Pills that appear, go or change width slide into place instead of jumping.
        LayoutTransition slide = new LayoutTransition();
        slide.enableTransitionType(LayoutTransition.CHANGING);
        row.setLayoutTransition(slide);

        LinearLayout clock = new LinearLayout(this);
        clock.setOrientation(LinearLayout.VERTICAL);
        time = text(76, "sans-serif-light", 0xFFFFFFFF);
        time.setLetterSpacing(-0.02f);
        time.setIncludeFontPadding(false);
        date = text(26, "sans-serif-light", 0xD9FFFFFF);
        clock.addView(time);
        clock.addView(date);
        row.addView(clock, new LinearLayout.LayoutParams(0, -2, 1));

        row.addView(livePill(), barItem());
        weather = text(26, "sans-serif-medium", 0xF2FFFFFF);
        weather.setPadding(px(28), 0, px(28), 0);
        weather.setGravity(Gravity.CENTER);
        View weatherPill = button(weather, this::openForecast);
        weatherPill.setVisibility(View.GONE);
        row.addView(weatherPill, barItem());


        View settings = pill("Ajustes", this::openSettings);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, px(72));
        lp.leftMargin = px(20);
        row.addView(settings, lp);
        return row;
    }

    private LinearLayout.LayoutParams barItem() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, px(72));
        lp.leftMargin = px(20);
        return lp;
    }

    /** A pulsing red dot, the minute, the two crests and the score. */
    private View livePill() {
        live = new GlassView(this, u, px(36));
        live.setVisibility(View.GONE);
        live.setFocusable(true);
        live.setClickable(true);
        live.setOnClickListener(v -> {
            if (shownLive != null) openMatch(shownLive);
        });
        live.setOnFocusChangeListener((v, focused) -> {
            live.setLit(focused);
            live.animate().scaleX(focused ? 1.05f : 1f).scaleY(focused ? 1.05f : 1f).setDuration(140).start();
        });
        LinearLayout in = new LinearLayout(this);
        in.setGravity(Gravity.CENTER_VERTICAL);
        in.setPadding(px(16), 0, px(28), 0);
        live.addView(in, new FrameLayout.LayoutParams(-2, -1));
        liveContent = in;

        LiveDot dot = new LiveDot(this, u);
        in.addView(dot, new LinearLayout.LayoutParams(dot.size(), dot.size()));
        pulse = dot.pulse;

        liveClock = text(24, "sans-serif-medium", 0xFFFF6961);
        liveClock.setPadding(px(14), 0, px(16), 0);
        in.addView(liveClock);
        liveHome = crest();
        in.addView(liveHome, new LinearLayout.LayoutParams(px(36), px(36)));
        liveScore = text(26, "sans-serif-medium", 0xFFFFFFFF);
        liveScore.setPadding(px(12), 0, px(12), 0);
        in.addView(liveScore);
        liveAway = crest();
        in.addView(liveAway, new LinearLayout.LayoutParams(px(36), px(36)));
        return live;
    }

    private ImageView crest() {
        ImageView i = new ImageView(this);
        i.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return i;
    }

    private void updateClock() {
        Date now = new Date();
        time.setText(new SimpleDateFormat(config.get(Config.CLOCK) == 0 ? "HH:mm" : "h:mm", ES).format(now));
        date.setText(new SimpleDateFormat("EEEE d 'de' MMMM", ES).format(now));
    }

    // ---- Apps ----

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Map<String, AppTile.App> found = new LinkedHashMap<>();
        // TV activities first, so an app with both keeps its TV one and its banner.
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
                ActivityInfo ai = ri.activityInfo;
                if (ai.packageName.equals(getPackageName()) || found.containsKey(ai.packageName)) continue;
                ComponentName cn = new ComponentName(ai.packageName, ai.name);
                Object label = ai.loadLabel(pm);
                found.put(ai.packageName, new AppTile.App(cn, label == null ? ai.packageName : label.toString(),
                        ai.loadBanner(pm), ai.loadIcon(pm)));
            }
        }
        List<AppTile.App> sorted = new ArrayList<>(found.values());
        Collator collator = Collator.getInstance(ES);
        sorted.sort((a, b) -> collator.compare(a.label, b.label));
        apps.clear();
        for (AppTile.App a : sorted) apps.put(a.component.getPackageName(), a);
    }

    private List<String> favorites() {
        String saved = prefs.getString(FAVORITES, null);
        List<String> list = new ArrayList<>();
        if (saved == null) {
            for (String p : SUGGESTED) if (apps.containsKey(p)) list.add(p);
            saveFavorites(list);
        } else if (!saved.isEmpty()) {
            for (String p : saved.split(",")) if (apps.containsKey(p)) list.add(p);
        }
        return list;
    }

    private void saveFavorites(List<String> list) {
        prefs.edit().putString(FAVORITES, String.join(",", list)).apply();
    }

    /** Slides the row just enough to keep the focused card inside the side margins. */
    private void slideTo(View tile) {
        float t = favoritesRow.getTranslationX();
        float left = px(SIDE) + tile.getLeft() + t, right = left + tile.getWidth();
        if (left < px(SIDE)) t += px(SIDE) - left;
        else if (right > root.getWidth() - px(SIDE)) t -= right - (root.getWidth() - px(SIDE));
        favoritesRow.animate().translationX(Math.min(0, t)).setDuration(220)
                .setUpdateListener(a -> GlassView.refresh(favoritesRow)).start();
    }

    /** The bottom row: the way into every app, then the favorites. */
    private void buildFavorites() {
        favoritesRow.removeAllViews();
        AppTile all = new AppTile(this, u, new AppTile.App(null, "Todas las apps", null, new GridIcon()));
        all.setOnClickListener(v -> openApps());
        favoritesRow.addView(all, new LinearLayout.LayoutParams(tileW, tileH));
        for (String p : favorites()) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tileW, tileH);
            lp.leftMargin = px(GAP);
            favoritesRow.addView(tile(apps.get(p)), lp);
        }
        int n = favoritesRow.getChildCount();
        favoritesRow.getLayoutParams().width = n * tileW + (n - 1) * px(GAP);
        favoritesRow.requestLayout();
    }

    private AppTile tile(AppTile.App app) {
        AppTile t = new AppTile(this, u, app);
        t.setOnClickListener(v -> {
            Intent open = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(app.component);
            if (app.component.getPackageName().endsWith(".settings")) openInPlace(open);
            else launch(open.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        });
        t.setOnLongClickListener(v -> {
            openSheet(t);
            return true;
        });
        return t;
    }

    /** The first favorite, or the way into every app when there are none yet. */
    private void focusFirst() {
        int n = favoritesRow.getChildCount();
        if (n == 0) return;
        View first = favoritesRow.getChildAt(n > 1 ? 1 : 0);
        first.post(first::requestFocus);
    }

    /**
     * TV settings is a translucent side panel. Opened inside this task it keeps the launcher
     * visible behind it; in a task of its own, Android hides the launcher and leaves black.
     */
    private void openInPlace(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException ignored) {
            // A box without a settings app; nothing to open.
        }
    }

    private void launch(Intent intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException ignored) {
            // The app went away or does not want to be opened from outside; nothing to do.
        }
    }

    // ---- Every app ----

    private void openApps() {
        if (appsLayer != null) return;
        appsLayer = new FrameLayout(this);
        ScrollView scroll = new ScrollView(this) {
            @Override protected void onScrollChanged(int l, int t, int ol, int ot) {
                super.onScrollChanged(l, t, ol, ot);
                GlassView.refresh(this);
            }
        };
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipChildren(false);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        column.setPadding(px(SIDE), px(56), px(SIDE), px(120));
        TextView title = text(64, "sans-serif-light", 0xFFFFFFFF);
        title.setText("Apps");
        column.addView(title);
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        grid.setPadding(0, px(40), 0, 0);
        column.addView(grid);
        scroll.addView(column, new FrameLayout.LayoutParams(-1, -2));
        appsLayer.addView(scroll, match());

        home.setVisibility(View.INVISIBLE);
        root.addView(appsLayer, match());
        buildGrid();
        View first = ((ViewGroup) grid.getChildAt(0)).getChildAt(0);
        first.post(first::requestFocus);
    }

    private void buildGrid() {
        grid.removeAllViews();
        LinearLayout row = null;
        int i = 0;
        for (AppTile.App app : apps.values()) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
                if (i > 0) rp.topMargin = px(74);
                grid.addView(row, rp);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tileW, tileH);
            if (i % COLUMNS > 0) lp.leftMargin = px(GAP);
            row.addView(tile(app), lp);
            i++;
        }
    }

    private void closeApps() {
        if (appsLayer == null) return;
        root.removeView(appsLayer);
        appsLayer = null;
        grid = null;
        home.setVisibility(View.VISIBLE);
        favoritesRow.getChildAt(0).requestFocus();
    }

    // ---- The menu for one app ----

    private void openSheet(AppTile tile) {
        AppTile.App app = tile.app;
        if (app.component == null) return;
        String pkg = app.component.getPackageName();
        List<String> favs = favorites();
        int at = favs.indexOf(pkg);

        sheetPackage = pkg;
        newSheet();

        GlassView panel = new GlassView(this, u, px(44));
        panel.setElevation(px(30));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(px(40), px(36), px(40), px(40));
        panel.addView(list);

        TextView title = text(40, "sans-serif-medium", 0xFFFFFFFF);
        title.setText(app.label);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, px(12));
        list.addView(title);

        if (at < 0) {
            sheetButton(list, "Agregar a favoritos", () -> { favs.add(pkg); saveFavorites(favs); });
        } else {
            sheetButton(list, "Quitar de favoritos", () -> { favs.remove(pkg); saveFavorites(favs); });
            if (at > 0) sheetButton(list, "Mover a la izquierda", () -> move(favs, at, at - 1));
            if (at < favs.size() - 1) sheetButton(list, "Mover a la derecha", () -> move(favs, at, at + 1));
        }
        sheetButton(list, "Información de la app", () -> launch(
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg))));
        sheetButton(list, "Cancelar", null);

        sheet.addView(panel, new FrameLayout.LayoutParams(px(680), -2, Gravity.CENTER));
        root.addView(sheet, match());
        list.getChildAt(1).requestFocus();
    }

    /** The dimmed layer for a menu or a panel; what is behind it can't take focus meanwhile. */
    private void newSheet() {
        sheetReturn = getCurrentFocus();
        sheetBlocks = appsLayer != null ? appsLayer : home;
        sheetBlocks.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        sheet = new FrameLayout(this);
        sheet.setBackgroundColor(0x80000000);
        sheet.setClickable(true);
    }

    /** The next seven days in Salta, in a glass panel. */
    private void openForecast() {
        if (forecast == null) return;
        sheetPackage = null;
        newSheet();
        GlassView panel = new GlassView(this, u, px(44));
        panel.setElevation(px(30));
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(px(48), px(40), px(48), px(40));
        panel.addView(column);

        TextView title = text(38, "sans-serif-medium", 0xFFFFFFFF);
        title.setText(config.shown(Config.CITY) + " · próximos 7 días");
        column.addView(title);

        LinearLayout days = new LinearLayout(this);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(-1, -2);
        dp.topMargin = px(32);
        column.addView(days, dp);
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        SimpleDateFormat name = new SimpleDateFormat("EEE d", ES);
        for (int i = 0; i < forecast.days.length; i++) {
            Weather.Day d = forecast.days[i];
            String label;
            try {
                label = i == 0 ? "Hoy" : name.format(iso.parse(d.date)).replace(".", "");
            } catch (java.text.ParseException e) {
                label = d.date;
            }
            LinearLayout day = new LinearLayout(this);
            day.setOrientation(LinearLayout.VERTICAL);
            day.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView l = text(26, "sans-serif-medium", 0xE6FFFFFF);
            l.setText(label.substring(0, 1).toUpperCase(ES) + label.substring(1));
            TextView icon = text(56, "sans-serif", 0xFFFFFFFF);
            icon.setText(d.icon);
            icon.setPadding(0, px(10), 0, px(6));
            TextView max = text(34, "sans-serif-medium", 0xFFFFFFFF);
            max.setText(d.max + "°");
            TextView min = text(26, "sans-serif", 0xA6FFFFFF);
            min.setText(d.min + "°");
            TextView rain = text(22, "sans-serif-medium", d.rain >= 40 ? 0xFF7CC4FF : 0x8CFFFFFF);
            rain.setText("💧 " + d.rain + "%");
            rain.setPadding(0, px(8), 0, 0);
            day.addView(l);
            day.addView(icon);
            day.addView(max);
            day.addView(min);
            day.addView(rain);
            days.addView(day, new LinearLayout.LayoutParams(0, -2, 1));
        }

        View close = pill("Cerrar", this::closeSheet);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(px(260), px(76));
        cp.topMargin = px(36);
        column.addView(close, cp);

        sheet.addView(panel, new FrameLayout.LayoutParams(px(1360), -2, Gravity.CENTER));
        root.addView(sheet, match());
        close.requestFocus();
    }

    /**
     * The launcher's own settings: each row shows its value, and OK moves to the next one. Every
     * change applies at once. The first row leads to Android's settings.
     */
    private void openSettings() {
        sheetPackage = null;
        newSheet();
        GlassView panel = new GlassView(this, u, px(44));
        panel.setElevation(px(30));
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(px(44), px(36), px(44), px(36));
        panel.addView(column);

        TextView title = text(38, "sans-serif-medium", 0xFFFFFFFF);
        title.setText("Vidrio Launcher");
        column.addView(title);

        ScrollView scroll = new ScrollView(this) {
            @Override protected void onScrollChanged(int l, int t, int ol, int ot) {
                super.onScrollChanged(l, t, ol, ot);
                GlassView.refresh(this);
            }
        };
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setClipChildren(false);
        rows.setPadding(px(10), px(10), px(10), px(10));
        scroll.addView(rows);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, px(700));
        sp.topMargin = px(16);
        column.addView(scroll, sp);

        View first = settingRow(rows, "Configuración de Android", "›", null);
        for (Config.Setting s : Config.ALL) settingRow(rows, s.label, config.shown(s), s);
        settingRow(rows, "Foto nueva ahora", "›", null).setOnClickListener(v -> {
            closeSheet();
            refreshWallpaper(true);
        });
        first.setOnClickListener(v -> {
            closeSheet();
            openInPlace(new Intent(Settings.ACTION_SETTINGS));
        });
        TextView version = text(22, "sans-serif", 0x80FFFFFF);
        try {
            version.setText("Versión " + getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
        } catch (PackageManager.NameNotFoundException ignored) {
            // Always found: it is this app.
        }
        version.setPadding(px(24), px(18), 0, 0);
        rows.addView(version);

        sheet.addView(panel, new FrameLayout.LayoutParams(px(980), -2, Gravity.CENTER));
        root.addView(sheet, match());
        first.requestFocus();
    }

    /** One row: the name on the left, the value on the right. OK on a setting moves it on. */
    private View settingRow(LinearLayout rows, String label, String value, Config.Setting setting) {
        GlassView g = new GlassView(this, u, px(30));
        g.setFocusable(true);
        g.setClickable(true);
        LinearLayout in = new LinearLayout(this);
        in.setGravity(Gravity.CENTER_VERTICAL);
        in.setPadding(px(30), 0, px(30), 0);
        g.addView(in, new FrameLayout.LayoutParams(-1, -1));
        TextView name = text(28, "sans-serif-medium", 0xFFFFFFFF);
        name.setText(label);
        in.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        TextView shown = text(26, "sans-serif", 0xB3FFFFFF);
        shown.setText(value);
        in.addView(shown);
        if (setting != null) {
            g.setOnClickListener(v -> {
                config.next(setting);
                shown.setText(config.shown(setting));
                applySetting(setting);
            });
        }
        g.setOnFocusChangeListener((v, focused) -> {
            g.setLit(focused);
            g.animate().scaleX(focused ? 1.03f : 1f).scaleY(focused ? 1.03f : 1f).setDuration(140).start();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, px(82));
        lp.topMargin = px(12);
        rows.addView(g, lp);
        return g;
    }

    /** Puts a changed setting to work right away. */
    private void applySetting(Config.Setting s) {
        if (s == Config.WALLPAPER) {
            if (photos()) {
                Wallpapers.Photo photo = wallpapers.cached(theme());
                if (photo != null) {
                    swapBackdrop(new Backdrop(photo.bitmap, screenW(), screenH()));
                    photo.bitmap.recycle();
                    credit.setText(photo.credit);
                }
                refreshWallpaper();
            } else {
                swapBackdrop(new Backdrop());
            }
            credit.setVisibility(photos() && config.on(Config.CREDIT) ? View.VISIBLE : View.GONE);
        } else if (s == Config.PERIOD) {
            refreshWallpaper();
        } else if (s == Config.CREDIT) {
            credit.setVisibility(photos() && config.on(Config.CREDIT) ? View.VISIBLE : View.GONE);
        } else if (s == Config.FOOTBALL || s == Config.TEAM) {
            Matches.favoriteTeam = Config.TEAM_NAMES[config.get(Config.TEAM)];
            main.removeCallbacks(pollMatches);
            refreshMatches();
        } else if (s == Config.WEATHER || s == Config.CITY) {
            weatherAt = 0;
            refreshWeather();
        } else if (s == Config.MUSIC) {
            nowPlaying.stop();
            if (config.on(Config.MUSIC)) nowPlaying.start();
            else nowPlaying.setVisibility(View.GONE);
        } else if (s == Config.LINGER) {
            nowPlaying.lingerMs = Config.LINGER_MINUTES[config.get(Config.LINGER)] * 60_000L;
        } else if (s == Config.CLOCK) {
            updateClock();
        }
    }

    /** The match in full: the score, and who scored and who was sent off, minute by minute. */
    private void openMatch(Matches.Match m) {
        sheetPackage = null;
        newSheet();
        GlassView panel = new GlassView(this, u, px(44));
        panel.setElevation(px(30));
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(px(56), px(40), px(56), px(40));
        panel.addView(column);
        matchBody = new LinearLayout(this);
        matchBody.setOrientation(LinearLayout.VERTICAL);
        column.addView(matchBody, new LinearLayout.LayoutParams(-1, -2));
        matchId = m.id;
        fillMatch(m);

        View close = pill("Cerrar", this::closeSheet);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(px(260), px(76));
        cp.topMargin = px(36);
        column.addView(close, cp);
        sheet.addView(panel, new FrameLayout.LayoutParams(px(1180), -2, Gravity.CENTER));
        root.addView(sheet, match());
        close.requestFocus();
    }

    private void fillMatch(Matches.Match m) {
        matchBody.removeAllViews();
        TextView league = text(26, "sans-serif-medium", 0xB3FFFFFF);
        SpannableString head = new SpannableString(m.league + "   " + (m.live ? "● " : "") + m.status);
        if (m.live) {
            int from = m.league.length() + 3;
            head.setSpan(new ForegroundColorSpan(0xFFFF6961), from, head.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        league.setText(head);
        league.setGravity(Gravity.CENTER);
        matchBody.addView(league, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout score = new LinearLayout(this);
        score.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = px(28);
        matchBody.addView(score, sp);
        score.addView(team(m.home), new LinearLayout.LayoutParams(0, -2, 1));
        TextView result = text(88, "sans-serif-light", 0xFFFFFFFF);
        result.setText(m.home.goals + " – " + m.away.goals);
        result.setGravity(Gravity.CENTER);
        score.addView(result, new LinearLayout.LayoutParams(px(300), -2));
        score.addView(team(m.away), new LinearLayout.LayoutParams(0, -2, 1));

        if (m.events.isEmpty()) {
            TextView none = text(26, "sans-serif", 0x99FFFFFF);
            none.setText("Todavía no hubo goles.");
            none.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2);
            np.topMargin = px(32);
            matchBody.addView(none, np);
            return;
        }
        // Each team's goals under its own crest, in the order they happened.
        LinearLayout lists = new LinearLayout(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = px(32);
        matchBody.addView(lists, lp);
        LinearLayout home = new LinearLayout(this), away = new LinearLayout(this);
        home.setOrientation(LinearLayout.VERTICAL);
        away.setOrientation(LinearLayout.VERTICAL);
        home.setGravity(Gravity.CENTER_HORIZONTAL);
        away.setGravity(Gravity.CENTER_HORIZONTAL);
        lists.addView(home, new LinearLayout.LayoutParams(0, -2, 1));
        lists.addView(new View(this), new LinearLayout.LayoutParams(px(300), 1));
        lists.addView(away, new LinearLayout.LayoutParams(0, -2, 1));
        for (Matches.Event e : m.events) {
            TextView line = text(27, "sans-serif", 0xF2FFFFFF);
            String note = e.penalty ? " (penal)" : e.ownGoal ? " (en contra)" : "";
            line.setText((e.goal ? "⚽  " : "🟥  ") + e.player + "  " + e.minute + note);
            line.setPadding(0, px(6), 0, px(6));
            (e.home ? home : away).addView(line);
        }
    }

    /** A crest with the team's name under it. */
    private View team(Matches.Side side) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView crest = crest();
        crest.setImageBitmap(side.logo == null ? null : logos.get(side.logo));
        t.addView(crest, new LinearLayout.LayoutParams(px(110), px(110)));
        TextView name = text(32, "sans-serif-medium", 0xFFFFFFFF);
        name.setText(side.name);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, px(14), 0, 0);
        t.addView(name);
        return t;
    }

    private void sheetButton(LinearLayout list, String label, Runnable action) {
        View b = pill(label, () -> {
            if (action != null) {
                action.run();
                buildFavorites();
                if (appsLayer != null) buildGrid();
            }
            closeSheet();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, px(84));
        lp.topMargin = px(16);
        list.addView(b, lp);
    }

    private void move(List<String> favs, int from, int to) {
        favs.add(to, favs.remove(from));
        saveFavorites(favs);
    }

    /** Closes the menu and puts focus back on the app's card, wherever it ended up. */
    private void closeSheet() {
        if (sheet == null) return;
        root.removeView(sheet);
        sheet = null;
        matchBody = null;
        sheetBlocks.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        View back = sheetPackage == null ? sheetReturn : findTile(sheetBlocks, sheetPackage);
        if (back != null && back.isAttachedToWindow()) back.requestFocus();
        else if (sheetBlocks == home) focusFirst();
    }

    private static View findTile(View v, String pkg) {
        if (v instanceof AppTile) {
            AppTile.App app = ((AppTile) v).app;
            return app.component != null && app.component.getPackageName().equals(pkg) ? v : null;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = findTile(g.getChildAt(i), pkg);
                if (found != null) return found;
            }
        }
        return null;
    }

    // ---- Small builders ----

    /** A focusable glass button with a label. */
    private View pill(String label, Runnable action) {
        TextView t = text(28, "sans-serif-medium", 0xFFFFFFFF);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setPadding(px(34), 0, px(34), 0);
        return button(t, action);
    }

    /** Puts content on a focusable glass button. */
    private View button(View content, Runnable action) {
        GlassView g = new GlassView(this, u, px(36));
        g.setFocusable(true);
        g.setClickable(true);
        g.addView(content, new FrameLayout.LayoutParams(-2, -1, Gravity.CENTER));
        g.setOnClickListener(v -> action.run());
        g.setOnFocusChangeListener((v, focused) -> {
            g.setLit(focused);
            g.animate().scaleX(focused ? 1.05f : 1f).scaleY(focused ? 1.05f : 1f).setDuration(140).start();
        });
        return g;
    }

    private TextView text(float size, String family, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * u);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setTextColor(color);
        t.setShadowLayer(12 * u, 0, 2 * u, 0x66000000);
        return t;
    }

    private int px(float v) {
        return Math.round(v * u);
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(-1, -1);
    }

    private static IntentFilter packageFilter() {
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        return f;
    }

    /** Nine rounded squares: the icon for every app. */
    private static final class GridIcon extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        GridIcon() {
            p.setColor(0xE6FFFFFF);
        }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            float cell = b.width() / 3f, gap = cell * 0.24f, r = cell * 0.22f;
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    float x = b.left + i * cell + gap / 2, y = b.top + j * cell + gap / 2;
                    c.drawRoundRect(x, y, x + cell - gap, y + cell - gap, r, r, p);
                }
            }
        }

        @Override public void setAlpha(int alpha) {
            p.setAlpha(alpha);
        }

        @Override public void setColorFilter(ColorFilter filter) {
            p.setColorFilter(filter);
        }

        @Override public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
