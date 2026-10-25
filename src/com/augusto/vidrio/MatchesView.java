package com.augusto.vidrio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The football widget: a glass card with what is being played and the day's Boca and Inter Miami
 * matches. It draws everything itself, with no child views, so it stays cheap to repaint.
 */
final class MatchesView extends GlassView {
    private static final int RED = 0xFFFF453A;

    private List<Matches.Match> matches = Collections.emptyList();
    private Map<String, Bitmap> logos = Collections.emptyMap();
    private boolean loaded;

    private final TextPaint title = paint(30, "sans-serif-medium", 0xFFFFFFFF);
    private final TextPaint small = paint(20, "sans-serif-medium", 0x99FFFFFF);
    private final TextPaint team = paint(27, "sans-serif", 0xF2FFFFFF);
    private final TextPaint score = paint(30, "sans-serif-medium", 0xFFFFFFFF);
    private final TextPaint status = paint(24, "sans-serif-medium", 0xD9FFFFFF);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlight = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF r = new RectF();

    private final float pad, head, row;

    MatchesView(Context context, float u) {
        super(context, u, 34 * u);
        pad = 30 * u;
        head = 78 * u;
        row = 92 * u;
        dot.setColor(RED);
        highlight.setColor(0x14FFFFFF);
        score.setTextAlign(Paint.Align.CENTER);
        status.setTextAlign(Paint.Align.RIGHT);
        setElevation(4 * u);
    }

    private TextPaint paint(float size, String family, int color) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(size * u);
        p.setTypeface(Typeface.create(family, Typeface.NORMAL));
        p.setColor(color);
        p.setShadowLayer(6 * u, 0, u, 0x4D000000);
        return p;
    }

    void show(Matches.Board board, Map<String, Bitmap> logos) {
        this.matches = board.matches;
        this.logos = logos;
        loaded = true;
        requestLayout();
        invalidate();
    }

    @Override protected void onMeasure(int w, int h) {
        int rows = Math.max(1, matches.size());
        setMeasuredDimension(MeasureSpec.getSize(w), (int) (head + rows * row + pad * 0.6f));
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth();
        boolean anyLive = false;
        for (Matches.Match m : matches) anyLive |= m.live;

        float y = pad + 30 * u;
        float x = pad;
        if (anyLive) {
            c.drawCircle(x + 7 * u, y - 10 * u, 7 * u, dot);
            x += 26 * u;
        }
        c.drawText(anyLive ? "En vivo" : "Fútbol hoy", x, y, title);
        small.setTextAlign(Paint.Align.RIGHT);
        c.drawText("ESPN", w - pad, y, small);
        small.setTextAlign(Paint.Align.LEFT);

        if (matches.isEmpty()) {
            c.drawText(loaded ? "No hay partidos en vivo ahora." : "Buscando partidos…", pad, head + 44 * u, team);
            return;
        }

        float logo = 38 * u, statusW = 132 * u, scoreW = 104 * u;
        float nameW = (w - 2 * pad - 2 * logo - 2 * 12 * u - scoreW - statusW - 18 * u) / 2;
        for (int i = 0; i < matches.size(); i++) {
            Matches.Match m = matches.get(i);
            float top = head + i * row;
            if (m.favorite) {
                r.set(pad * 0.4f, top, w - pad * 0.4f, top + row - 8 * u);
                c.drawRoundRect(r, 18 * u, 18 * u, highlight);
            }
            c.drawText(m.league, pad, top + 26 * u, small);
            float mid = top + 64 * u;

            float hx = pad;
            drawLogo(c, m.home.logo, hx, mid - logo * 0.72f, logo);
            hx += logo + 12 * u;
            c.drawText(fit(m.home.name, nameW), hx, mid, team);

            float sx = hx + nameW + scoreW / 2;
            String result = m.home.goals.isEmpty() ? "–" : m.home.goals + " – " + m.away.goals;
            c.drawText(result, sx, mid, score);

            float ax = sx + scoreW / 2;
            team.setTextAlign(Paint.Align.RIGHT);
            c.drawText(fit(m.away.name, nameW), ax + nameW, mid, team);
            team.setTextAlign(Paint.Align.LEFT);
            drawLogo(c, m.away.logo, ax + nameW + 12 * u, mid - logo * 0.72f, logo);

            status.setColor(m.live ? RED : 0xD9FFFFFF);
            c.drawText(m.status, w - pad, mid, status);
        }
    }

    private void drawLogo(Canvas c, String url, float x, float y, float size) {
        Bitmap b = url == null ? null : logos.get(url);
        if (b == null) return;
        r.set(x, y, x + size, y + size);
        c.drawBitmap(b, null, r, image);
    }

    private String fit(String s, float width) {
        return TextUtils.ellipsize(s, team, width, TextUtils.TruncateAt.END).toString();
    }
}
