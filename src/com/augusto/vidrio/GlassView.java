package com.augusto.vidrio;

import android.content.Context;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.widget.FrameLayout;

/**
 * A pane of frosted glass. It paints the frosted wallpaper lined up with the screen, so the pane
 * looks see-through, then a light tint, a sheen across the top and a bright edge.
 */
class GlassView extends FrameLayout {
    static Backdrop backdrop;

    final float u;
    float radius;
    private boolean lit;

    private final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private android.graphics.Bitmap shaderOf;
    private final RectF box = new RectF(), rim = new RectF();

    GlassView(Context context, float u, float radius) {
        super(context);
        this.u = u;
        this.radius = radius;
        setWillNotDraw(false);
        setClipChildren(false);
        edge.setStyle(Paint.Style.STROKE);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), GlassView.this.radius);
            }
        });
        setLit(false);
    }

    /** The focused look: clearer glass and a white rim. */
    void setLit(boolean on) {
        lit = on;
        tint.setColor(on ? 0x38FFFFFF : 0x14FFFFFF);
        edge.setStrokeWidth((on ? 3f : 1.5f) * u);
        updateEdge();
        invalidate();
    }

    boolean isLit() {
        return lit;
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        box.set(0, 0, w, h);
        sheen.setShader(new LinearGradient(0, 0, 0, h * 0.55f, 0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        updateEdge();
    }

    private void updateEdge() {
        float s = edge.getStrokeWidth() / 2;
        rim.set(box.left + s, box.top + s, box.right - s, box.bottom - s);
        if (box.width() == 0) return;
        if (lit) {
            edge.setShader(null);
            edge.setColor(0xF2FFFFFF);
        } else {
            edge.setShader(new LinearGradient(0, 0, box.width(), box.height(),
                    new int[]{0x99FFFFFF, 0x14FFFFFF, 0x47FFFFFF}, null, Shader.TileMode.CLAMP));
        }
    }

    @Override protected void onDraw(Canvas c) {
        // Where this pane sits on screen. Scale is left out on purpose: a focused tile magnifies
        // its glass a little, which reads as the lens effect it is meant to be.
        float x = getLeft(), y = getTop();
        for (ViewParent p = getParent(); p instanceof View; p = p.getParent()) {
            View v = (View) p;
            x += v.getLeft() - v.getScrollX() + v.getTranslationX();
            y += v.getTop() - v.getScrollY() + v.getTranslationY();
        }
        // The wallpaper can change under a pane that already exists.
        if (shaderOf != backdrop.frosted) {
            shaderOf = backdrop.frosted;
            glass.setShader(new BitmapShader(shaderOf, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        }
        View root = getRootView();
        matrix.setScale(root.getWidth() / (float) backdrop.frosted.getWidth(),
                root.getHeight() / (float) backdrop.frosted.getHeight());
        matrix.postTranslate(-x, -y);
        glass.getShader().setLocalMatrix(matrix);

        c.drawRoundRect(box, radius, radius, glass);
        c.drawRoundRect(box, radius, radius, tint);
        c.drawRoundRect(box, radius, radius, sheen);
        float r = radius - edge.getStrokeWidth() / 2;
        c.drawRoundRect(rim, r, r, edge);
    }

    /** Scrolling moves the panes but not the wallpaper, so every pane has to repaint. */
    static void refresh(View v) {
        if (v instanceof GlassView) v.invalidate();
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) refresh(g.getChildAt(i));
        }
    }
}
