package com.augusto.vidrio;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;

/**
 * The wallpaper, painted once at startup, and its frosted copy that every glass surface shows.
 * Both are tiny and get scaled up to the screen: they are blurry on purpose, so nothing is lost.
 */
final class Backdrop {
    static final int W = 480, H = 270;

    final Bitmap wallpaper;
    final Bitmap frosted;

    Backdrop() {
        wallpaper = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(wallpaper);
        c.drawColor(0xFF0A0F24);
        blob(c, 90, 70, 210, 0xFF2F6BFF);
        blob(c, 300, 20, 190, 0xFF8E4DFF);
        blob(c, 430, 230, 200, 0xFF00BFA6);
        blob(c, 150, 260, 170, 0xFFFF6B6B);
        blob(c, 475, 45, 120, 0xCCFFB547);
        // Darker at the top and bottom, so the clock and the labels stay readable.
        Paint shade = new Paint();
        shade.setShader(new LinearGradient(0, 0, 0, H,
                new int[]{0x66000000, 0x00000000, 0x00000000, 0x59000000},
                new float[]{0f, 0.35f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, shade);
        blur(wallpaper, 8);
        frosted = frost(wallpaper, 1.08f, 14);
    }

    /**
     * A photo for wallpaper: cropped to fill the screen, darkened at the top and the bottom so
     * the clock and the cards stay readable, and frosted for the glass.
     */
    Backdrop(Bitmap photo, int screenW, int screenH) {
        wallpaper = Bitmap.createBitmap(screenW, screenH, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(wallpaper);
        float scale = Math.max(screenW / (float) photo.getWidth(), screenH / (float) photo.getHeight());
        float w = photo.getWidth() * scale, h = photo.getHeight() * scale;
        c.drawBitmap(photo, null, new android.graphics.RectF((screenW - w) / 2, (screenH - h) / 2,
                (screenW + w) / 2, (screenH + h) / 2), new Paint(Paint.FILTER_BITMAP_FLAG));
        Paint shade = new Paint();
        shade.setShader(new LinearGradient(0, 0, 0, screenH,
                new int[]{0x8C000000, 0x14000000, 0x14000000, 0x99000000},
                new float[]{0f, 0.4f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, screenW, screenH, shade);
        // Photos are bright: their glass darkens a little instead of lifting, or it washes out.
        frosted = frost(wallpaper, 0.7f, 0);
    }

    /** Small, blurred and saturated; gain and lift set how much lighter the glass is. */
    private static Bitmap frost(Bitmap source, float gain, int lift) {
        Bitmap small = Bitmap.createBitmap(W / 2, H / 2, Bitmap.Config.ARGB_8888);
        new Canvas(small).drawBitmap(source, null, new android.graphics.Rect(0, 0, W / 2, H / 2),
                new Paint(Paint.FILTER_BITMAP_FLAG));
        blur(small, 6);
        // Glass lifts and saturates what is behind it a little, like the real thing.
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(1.45f);
        cm.postConcat(new ColorMatrix(new float[]{
                gain, 0, 0, 0, lift,
                0, gain, 0, 0, lift,
                0, 0, gain, 0, lift + 4,
                0, 0, 0, 1, 0}));
        Bitmap frosted = Bitmap.createBitmap(W / 2, H / 2, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(new ColorMatrixColorFilter(cm));
        new Canvas(frosted).drawBitmap(small, 0, 0, p);
        small.recycle();
        return frosted;
    }

    private static void blob(Canvas c, float x, float y, float r, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(x, y, r, color, color & 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, r, p);
    }

    /** Three passes of a box blur, which together look like a Gaussian one. */
    static void blur(Bitmap b, int r) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h], tmp = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int i = 0; i < 3; i++) {
            box(px, tmp, w, h, r, 1, w);
            box(tmp, px, h, w, r, w, 1);
        }
        b.setPixels(px, 0, w, 0, 0, w, h);
    }

    /** Blurs every line of n pixels; step moves along a line, stride from one line to the next. */
    private static void box(int[] in, int[] out, int n, int lines, int r, int step, int stride) {
        int div = 2 * r + 1;
        for (int l = 0; l < lines; l++) {
            int base = l * stride;
            int a = 0, red = 0, g = 0, bl = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[base + clamp(i, n) * step];
                a += p >>> 24; red += (p >> 16) & 255; g += (p >> 8) & 255; bl += p & 255;
            }
            for (int i = 0; i < n; i++) {
                out[base + i * step] = ((a / div) << 24) | ((red / div) << 16) | ((g / div) << 8) | (bl / div);
                int pOut = in[base + clamp(i - r, n) * step];
                int pIn = in[base + clamp(i + r + 1, n) * step];
                a += (pIn >>> 24) - (pOut >>> 24);
                red += ((pIn >> 16) & 255) - ((pOut >> 16) & 255);
                g += ((pIn >> 8) & 255) - ((pOut >> 8) & 255);
                bl += (pIn & 255) - (pOut & 255);
            }
        }
    }

    private static int clamp(int i, int n) {
        return i < 0 ? 0 : i >= n ? n - 1 : i;
    }
}
