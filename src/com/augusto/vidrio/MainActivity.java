package com.augusto.vidrio;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
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
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
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
    private TextView time, date, network;
    /** The screen with every app; null while closed. */
    private FrameLayout appsLayer;
    private LinearLayout grid;
    private FrameLayout sheet;
    private ViewGroup sheetBlocks;
    private String sheetPackage;

    private final BroadcastReceiver clockReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            updateClock();
        }
    };
    private final BroadcastReceiver networkReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            updateNetwork();
        }
    };
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            loadApps();
            String pkg = i.getData() == null ? null : i.getData().getSchemeSpecificPart();
            boolean fresh = Intent.ACTION_PACKAGE_ADDED.equals(i.getAction())
                    && !i.getBooleanExtra(Intent.EXTRA_REPLACING, false);
            if (fresh && apps.containsKey(pkg)) {
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
        wallpapers = new Wallpapers(this);
        Wallpapers.Photo photo = GlassView.backdrop == null ? wallpapers.cached() : null;
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
        credit.setText(prefsCredit());
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(px(760), -2, Gravity.TOP | Gravity.END);
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
        registerReceiver(networkReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
    }

    @Override protected void onResume() {
        super.onResume();
        visible = true;
        updateClock();
        updateNetwork();
        refreshWallpaper();
        refreshWeather();
        nowPlaying.start();
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
        unregisterReceiver(networkReceiver);
        super.onDestroy();
    }

    // ---- Widgets ----

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Bitmap> logos = new ConcurrentHashMap<>();
    private final Runnable pollMatches = this::refreshMatches;
    private Wallpapers wallpapers;
    private View wallpaperView;
    private TextView credit;
    private NowPlaying nowPlaying;
    private boolean visible, fetchingMatches;
    private long weatherAt;
    /** The live-match pill in the top bar: only there while something is being played. */
    private GlassView live;
    private View liveDot;
    private ImageView liveHome, liveAway;
    private TextView liveClock, liveScore, weather;
    private ObjectAnimator pulse;
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
                liveMatches = new ArrayList<>();
                for (Matches.Match m : board.matches) if (m.live) liveMatches.add(m);
                showLive();
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
        liveClock.setText(m.status);
        liveScore.setText(m.home.abbr + "  " + m.home.goals + " – " + m.away.goals + "  " + m.away.abbr);
        liveHome.setImageBitmap(m.home.logo == null ? null : logos.get(m.home.logo));
        liveAway.setImageBitmap(m.away.logo == null ? null : logos.get(m.away.logo));
        live.setVisibility(View.VISIBLE);
        if (pulse.isPaused()) pulse.resume();
        else if (!pulse.isStarted()) pulse.start();
        if (liveMatches.size() > 1 && visible) main.postDelayed(rotateLive, 8_000);
    }

    /** Every half hour at most, and only while the launcher is on screen. */
    private void refreshWeather() {
        if (System.currentTimeMillis() - weatherAt < 30 * 60_000) return;
        worker.execute(() -> {
            Weather w;
            try {
                w = Weather.fetch();
            } catch (Exception e) {
                return;
            }
            main.post(() -> {
                weatherAt = System.currentTimeMillis();
                weather.setText(w.icon + "  " + w.now + "°   " + w.max + "° / " + w.min + "°");
                ((View) weather.getParent()).setVisibility(View.VISIBLE);
            });
        });
    }

    /** A new photo of Salta once a day; the glass follows the wallpaper. */
    private void refreshWallpaper() {
        worker.execute(() -> {
            Wallpapers.Photo photo;
            try {
                photo = wallpapers.today(false);
            } catch (Exception e) {
                return;
            }
            if (photo == null) return;
            Backdrop backdrop = new Backdrop(photo.bitmap, screenW(), screenH());
            photo.bitmap.recycle();
            main.post(() -> {
                if (isDestroyed()) return;
                GlassView.backdrop = backdrop;
                credit.setText(photo.credit);
                wallpaperView.invalidate();
                GlassView.refresh(root);
            });
        });
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
        GlassView weatherPill = new GlassView(this, u, px(36));
        weatherPill.addView(weather, new FrameLayout.LayoutParams(-2, -1, Gravity.CENTER));
        weatherPill.setVisibility(View.GONE);
        row.addView(weatherPill, barItem());

        network = text(26, "sans-serif-medium", 0xF2FFFFFF);
        network.setPadding(px(30), 0, px(30), 0);
        network.setGravity(Gravity.CENTER);
        row.addView(button(network, () -> openInPlace(new Intent(Settings.ACTION_WIFI_SETTINGS))), barItem());

        View settings = pill("Ajustes", () -> openInPlace(new Intent(Settings.ACTION_SETTINGS)));
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
        LinearLayout in = new LinearLayout(this);
        in.setGravity(Gravity.CENTER_VERTICAL);
        in.setPadding(px(26), 0, px(28), 0);
        // The dot grows past its own box while it pulses.
        in.setClipChildren(false);
        live.addView(in, new FrameLayout.LayoutParams(-2, -1));

        liveDot = new View(this);
        android.graphics.drawable.GradientDrawable red = new android.graphics.drawable.GradientDrawable();
        red.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        red.setColor(0xFFFF453A);
        liveDot.setBackground(red);
        in.addView(liveDot, new LinearLayout.LayoutParams(px(16), px(16)));
        // Animates only the dot's scale and alpha, which the GPU does without repainting the pill.
        pulse = ObjectAnimator.ofPropertyValuesHolder(liveDot,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 0.85f, 1.3f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.85f, 1.3f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.35f));
        pulse.setDuration(1300);
        pulse.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        pulse.setRepeatCount(ObjectAnimator.INFINITE);
        pulse.setRepeatMode(ObjectAnimator.REVERSE);

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
        time.setText(new SimpleDateFormat("HH:mm", ES).format(now));
        date.setText(new SimpleDateFormat("EEEE d 'de' MMMM", ES).format(now));
    }

    private void updateNetwork() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo info = cm == null ? null : cm.getActiveNetworkInfo();
        boolean up = info != null && info.isConnected();
        String label = up ? (info.getType() == ConnectivityManager.TYPE_ETHERNET ? "Cable" : "Wi-Fi") : "Sin conexión";
        SpannableString s = new SpannableString("●  " + label);
        s.setSpan(new ForegroundColorSpan(up ? 0xFF30D158 : 0xFFFF453A), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        network.setText(s);
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
        sheetBlocks = appsLayer != null ? appsLayer : home;
        sheetBlocks.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        sheet = new FrameLayout(this);
        sheet.setBackgroundColor(0x80000000);
        sheet.setClickable(true);

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
        sheetBlocks.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        View back = findTile(sheetBlocks, sheetPackage);
        if (back != null) back.requestFocus();
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
