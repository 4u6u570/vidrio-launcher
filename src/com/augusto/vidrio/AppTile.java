package com.augusto.vidrio;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.text.TextPaint;

/**
 * One app on a glass card. TV apps bring a banner, which fills the card; the rest show their icon
 * and name. A focused card grows, lifts, and shows the app's name underneath, as on Apple TV.
 */
final class AppTile extends GlassView {

    static final class App {
        final ComponentName component;
        final String label;
        final Drawable banner, icon;

        App(ComponentName component, String label, Drawable banner, Drawable icon) {
            this.component = component;
            this.label = label;
            this.banner = banner;
            this.icon = icon;
        }
    }

    final App app;
    private final float pad;
    private final TextPaint inner = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint below = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint art = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF artBox = new RectF();
    private final Rect iconBox = new Rect();
    private Bitmap artBitmap;

    AppTile(Context context, float u, App app) {
        super(context, u, 30 * u);
        this.app = app;
        pad = 10 * u;
        setFocusable(true);
        setClickable(true);
        setLongClickable(true);
        setElevation(4 * u);
        setContentDescription(app.label);

        inner.setColor(0xF2FFFFFF);
        inner.setTextAlign(Paint.Align.CENTER);
        inner.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        inner.setShadowLayer(6 * u, 0, u, 0x59000000);
        below.set(inner);
        below.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        below.setShadowLayer(10 * u, 0, 2 * u, 0x99000000);

        setOnFocusChangeListener((v, focused) -> {
            setLit(focused);
            animate().scaleX(focused ? 1.1f : 1f).scaleY(focused ? 1.1f : 1f)
                    .translationZ(focused ? 26 * u : 0).setDuration(170).start();
            // Scroll far enough to show the grown card and the name under it, not just the card.
            if (focused) post(() -> requestRectangleOnScreen(new Rect(
                    (int) (-getWidth() * 0.06f), (int) (-getHeight() * 0.1f),
                    (int) (getWidth() * 1.06f), (int) (getHeight() * 1.06f + 70 * u))));
        });
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        inner.setTextSize(Math.min(26 * u, h * 0.15f));
        below.setTextSize(26 * u);
        if (artBitmap != null) artBitmap.recycle();
        artBitmap = null;
        if (app.banner != null && w > 0) {
            // Banners are drawn once into a bitmap so their corners can be rounded smoothly.
            artBox.set(pad, pad, w - pad, h - pad);
            artBitmap = Bitmap.createBitmap((int) artBox.width(), (int) artBox.height(), Bitmap.Config.ARGB_8888);
            app.banner.setBounds(0, 0, artBitmap.getWidth(), artBitmap.getHeight());
            app.banner.draw(new Canvas(artBitmap));
            BitmapShader s = new BitmapShader(artBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setTranslate(pad, pad);
            s.setLocalMatrix(m);
            art.setShader(s);
        } else {
            int size = (int) Math.min(h * 0.48f, w * 0.4f);
            int top = (int) (h * 0.14f);
            iconBox.set((w - size) / 2, top, (w + size) / 2, top + size);
        }
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        int w = getWidth(), h = getHeight();
        if (artBitmap != null) {
            float r = radius - pad * 0.7f;
            c.drawRoundRect(artBox, r, r, art);
            if (isLit()) {
                String name = TextUtils.ellipsize(app.label, below, w * 1.2f, TextUtils.TruncateAt.END).toString();
                c.drawText(name, w / 2f, h + 42 * u, below);
            }
        } else {
            app.icon.setBounds(iconBox);
            app.icon.draw(c);
            String name = TextUtils.ellipsize(app.label, inner, w - 4 * pad, TextUtils.TruncateAt.END).toString();
            c.drawText(name, w / 2f, h - h * 0.13f, inner);
        }
    }
}
