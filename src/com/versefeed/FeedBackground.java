package com.versefeed;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import java.util.Random;

/**
 * The feed's backdrop, drawn behind the (transparent) pages.
 * <ul>
 * <li>COLOR: a diagonal gradient whose colours blend from one verse's palette to the next as
 * you drag, with two soft glows that drift with the scroll.</li>
 * <li>BUBBLES: one constant grey gradient (light or dark) with slowly rising, heavily blurred
 * bubbles that also shift with the scroll (parallax).</li>
 * </ul>
 */
final class FeedBackground extends View {

    static final int STYLE_COLOR = 0;
    static final int STYLE_BUBBLES = 1;

    private static final int BUBBLE_COUNT = 14;
    private static final float BLUR_SPREAD = 2.4f; // glow radius / bubble radius

    private final int style;
    private final boolean night;
    private final boolean animate;
    private final float density;
    private final long t0 = SystemClock.uptimeMillis();

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shaded = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int w, h;
    private int colorA = 0xFF1E1B4B, colorB = 0xFF4338CA;
    private float travel;
    private RadialGradient glow;
    private Bubble[] bubbles;

    private static final class Bubble {
        float x, y, r, speed, phase, sway, depth;
        RadialGradient body;
    }

    FeedBackground(Context ctx, int style, boolean night) {
        super(ctx);
        this.style = style;
        this.night = night;
        this.density = ctx.getResources().getDisplayMetrics().density;
        this.animate = ValueAnimator.areAnimatorsEnabled();
    }

    private float travelBase, lastTravel;
    private ValueAnimator blend;

    /**
     * Blend from palette {@code from} toward {@code to} by {@code t} (0..1). {@code travel} is
     * measured from the start of the current feed (see {@link #startSegment}).
     */
    void update(int[] from, int[] to, float t, float travel) {
        lastTravel = travel;
        this.travel = travelBase + travel;
        boolean blending = blend != null && blend.isRunning();
        if (style == STYLE_COLOR && !(blending && t == 0f)) { // let a palette blend finish
            if (blending) blend.cancel(); // the user is dragging: follow the finger
            int a = lerp(from[0], to[0], t);
            int b = lerp(from[1], to[1], t);
            if (a != colorA || b != colorB) {
                colorA = a;
                colorB = b;
                buildFill();
            }
        }
        invalidate();
    }

    /** A new feed is starting: carry on the parallax from where the last one left it. */
    void startSegment() {
        travelBase += lastTravel;
        lastTravel = 0;
    }

    /** Smoothly shifts the Colorful gradient to another palette (e.g. moving between screens). */
    void animateTo(int[] palette) {
        if (style != STYLE_COLOR) return;
        final int a0 = colorA, b0 = colorB, a1 = palette[0], b1 = palette[1];
        if (a0 == a1 && b0 == b1) return;
        if (blend != null) blend.cancel();
        if (!ValueAnimator.areAnimatorsEnabled()) {
            colorA = a1;
            colorB = b1;
            buildFill();
            invalidate();
            return;
        }
        blend = ValueAnimator.ofFloat(0f, 1f);
        blend.setDuration(450);
        blend.addUpdateListener(an -> {
            float t = (float) an.getAnimatedValue();
            colorA = lerp(a0, a1, t);
            colorB = lerp(b0, b1, t);
            buildFill();
            invalidate();
        });
        blend.start();
    }

    @Override
    protected void onSizeChanged(int nw, int nh, int ow, int oh) {
        super.onSizeChanged(nw, nh, ow, oh);
        w = nw;
        h = nh;
        buildFill();
        float big = Math.max(w, h);
        glow = new RadialGradient(0, 0, big * 0.55f, new int[]{0x26FFFFFF, 0x0DFFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
        if (style == STYLE_BUBBLES) buildBubbles();
    }

    private void buildFill() {
        if (w <= 0 || h <= 0) return;
        if (style == STYLE_COLOR) {
            fill.setShader(new LinearGradient(0, 0, w, h, colorA, colorB, Shader.TileMode.CLAMP));
        } else {
            int[] c = night
                    ? new int[]{0xFF2A2C31, 0xFF17181B, 0xFF0A0A0C}
                    : new int[]{0xFFFCFCFD, 0xFFEFF1F4, 0xFFE0E3E8};
            fill.setShader(new LinearGradient(0, 0, w * 0.35f, h, c, new float[]{0f, 0.5f, 1f},
                    Shader.TileMode.CLAMP));
        }
    }

    private void buildBubbles() {
        Random rnd = new Random(7);
        float unit = Math.min(w, h);
        float peak = night ? 0.10f : 0.85f;
        bubbles = new Bubble[BUBBLE_COUNT];
        for (int i = 0; i < BUBBLE_COUNT; i++) {
            Bubble b = new Bubble();
            b.depth = 0.25f + rnd.nextFloat() * 0.75f; // nearer bubbles are bigger and faster
            float core = unit * (0.04f + 0.16f * b.depth * rnd.nextFloat() + 0.03f * b.depth);
            // Heavily blurred: the bubble's light is spread over a soft Gaussian-like glow
            // well beyond its edge, so no rim or highlight survives.
            b.r = core * BLUR_SPREAD;
            b.x = rnd.nextFloat();
            b.y = rnd.nextFloat();
            b.speed = density * (5f + 14f * b.depth);
            b.phase = rnd.nextFloat() * 6.283f;
            b.sway = density * (6f + 10f * rnd.nextFloat());
            float a = peak * (0.6f + 0.4f * rnd.nextFloat());
            b.body = new RadialGradient(0, 0, b.r,
                    new int[]{white(a), white(a * 0.88f), white(a * 0.61f), white(a * 0.32f),
                            white(a * 0.12f), white(0f)},
                    new float[]{0f, 0.2f, 0.4f, 0.6f, 0.8f, 1f}, Shader.TileMode.CLAMP);
            bubbles[i] = b;
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        if (w <= 0 || h <= 0) return;
        c.drawRect(0, 0, w, h, fill);
        float time = (SystemClock.uptimeMillis() - t0) / 1000f;
        if (style == STYLE_COLOR) {
            // Two soft lights that slowly wander on their own and swing with each swipe.
            drawGlow(c,
                    w * (0.18f + 0.10f * (float) Math.sin(time * 0.21f)),
                    h * (0.22f + 0.10f * (float) Math.sin(travel * 0.7f) + 0.05f * (float) Math.cos(time * 0.17f)));
            drawGlow(c,
                    w * (0.86f + 0.08f * (float) Math.cos(time * 0.19f + 1.3f)),
                    h * (0.80f + 0.10f * (float) Math.cos(travel * 0.55f) + 0.05f * (float) Math.sin(time * 0.15f)));
            if (animate) postInvalidateOnAnimation();
            return;
        }
        if (bubbles == null) return;
        for (Bubble b : bubbles) {
            float span = h + 2 * b.r;
            float y = b.y * span - time * b.speed - travel * b.depth * h * 0.35f;
            y = mod(y, span) - b.r;
            float x = b.x * w + (float) Math.sin(time * 0.35f + b.phase) * b.sway;
            c.save();
            c.translate(x, y);
            shaded.setShader(b.body);
            c.drawCircle(0, 0, b.r, shaded);
            c.restore();
        }
        if (animate) postInvalidateOnAnimation();
    }

    private void drawGlow(Canvas c, float x, float y) {
        c.save();
        c.translate(x, y);
        shaded.setShader(glow);
        c.drawCircle(0, 0, Math.max(w, h) * 0.55f, shaded);
        c.restore();
    }

    @Override
    public void onVisibilityAggregated(boolean visible) {
        super.onVisibilityAggregated(visible);
        if (visible) invalidate(); // restart the bubble animation
    }

    private static float mod(float a, float m) {
        float r = a % m;
        return r < 0 ? r + m : r;
    }

    private static int white(float a) {
        return (Math.round(Math.max(0f, Math.min(1f, a)) * 255) << 24) | 0xFFFFFF;
    }

    private static int lerp(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return 0xFF000000
                | (Math.round(ar + (br - ar) * t) << 16)
                | (Math.round(ag + (bg - ag) * t) << 8)
                | Math.round(ab + (bb - ab) * t);
    }
}
