package com.augusto.vidrio;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * The "live" mark: a steady red dot with a halo that grows from it and fades, once every
 * second and a half. The view is big enough to hold the whole halo, so nothing gets cut off,
 * and only this small view repaints while it moves.
 */
final class LiveDot extends View {
    private static final int RED = 0xFFFF453A;

    final ValueAnimator pulse = ValueAnimator.ofFloat(0f, 1f);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float core, reach;
    private float t;

    LiveDot(Context context, float u) {
        super(context);
        core = 7 * u;
        reach = 11 * u;
        dot.setColor(RED);
        halo.setColor(RED);
        pulse.setDuration(1500);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setInterpolator(new DecelerateInterpolator(1.4f));
        pulse.addUpdateListener(a -> {
            t = (float) a.getAnimatedValue();
            invalidate();
        });
    }

    /** Wide enough for the halo at its largest. */
    int size() {
        return (int) Math.ceil(2 * (core + reach) + 2);
    }

    @Override protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        halo.setAlpha((int) (140 * (1 - t)));
        c.drawCircle(cx, cy, core + reach * t, halo);
        c.drawCircle(cx, cy, core, dot);
    }
}
