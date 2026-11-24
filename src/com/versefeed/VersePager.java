package com.versefeed;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

/**
 * Full-screen vertical pager: one page per item, swipe up for the next item and down for the
 * previous one. Pages are identified by opaque long keys, so the adapter decides what comes
 * next (for example the second half of a long verse). Only three page views exist (previous,
 * current, next); they rotate as you swipe.
 */
final class VersePager extends FrameLayout {

    static final long NONE = -1;

    interface Adapter {
        View create(ViewGroup parent);

        void bind(View page, long key);

        /** Key of the page after {@code key}, or {@link #NONE}. */
        long next(long key);

        /** Key of the page before {@code key}, or {@link #NONE}. */
        long prev(long key);

        /**
         * Called every frame with each visible page's offset from the centre, in pages
         * (0 = settled on screen, -1 = one page above, 1 = one page below).
         */
        default void onOffset(View page, float offset) {
        }
    }

    interface Listener {
        void onPageChanged(long key);

        /**
         * Called on every frame of a drag or settle. {@code to} is the page being revealed
         * ({@link #NONE} if none), {@code t} runs 0..1 toward it, and {@code travel} is the total
         * distance moved in pages (fractional while dragging), for parallax effects.
         */
        void onScroll(long from, long to, float t, float travel);
    }

    private static final float DISTANCE_THRESHOLD = 0.12f; // fraction of the height
    private static final long MIN_SETTLE_MS = 90;
    private static final long MAX_SETTLE_MS = 200;
    private static final float DECELERATE = 1.5f;
    private static final float DEPTH_SCALE = 0.08f; // page scale lost one page away
    private static final float DEPTH_FADE = 0.5f; // page alpha lost one page away

    private final View[] pages = new View[3]; // previous, current, next
    private final long[] keys = {NONE, NONE, NONE};
    private Adapter adapter;
    private Listener listener;
    private float drag; // vertical offset of the current page, in px
    private int travel; // pages committed so far (+1 per next, -1 per previous)

    private final int touchSlop;
    private final float flingThreshold;
    private final int maxFling;
    private VelocityTracker velocity;
    private float downX, downY, lastY;
    private boolean dragging;
    private ValueAnimator anim;

    VersePager(Context ctx) {
        super(ctx);
        ViewConfiguration vc = ViewConfiguration.get(ctx);
        touchSlop = vc.getScaledTouchSlop();
        maxFling = vc.getScaledMaximumFlingVelocity();
        // A short, quick flick is enough to change pages.
        flingThreshold = 180 * getResources().getDisplayMetrics().density;
        setClipChildren(true);
    }

    void setAdapter(Adapter a, long start) {
        adapter = a;
        removeAllViews();
        for (int k = 0; k < 3; k++) {
            pages[k] = a.create(this);
            addView(pages[k], new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }
        keys[1] = start;
        keys[0] = a.prev(start);
        keys[2] = a.next(start);
        drag = 0;
        travel = 0;
        for (int k = 0; k < 3; k++) bindSlot(k);
        layoutPages();
    }

    View currentPage() {
        return pages[1];
    }

    void setListener(Listener l) {
        listener = l;
        layoutPages();
    }

    private void bindSlot(int k) {
        boolean valid = keys[k] != NONE;
        pages[k].setVisibility(valid ? VISIBLE : INVISIBLE);
        if (valid) adapter.bind(pages[k], keys[k]);
    }

    private void layoutPages() {
        int h = getHeight();
        for (int k = 0; k < 3; k++) {
            View page = pages[k];
            if (page == null) continue;
            float y = (k - 1) * h + drag;
            page.setTranslationY(y);
            // Depth: pages shrink and dim slightly as they move away from the centre.
            float off = h > 0 ? y / h : (k - 1);
            float a = Math.min(1f, Math.abs(off));
            float scale = 1f - DEPTH_SCALE * a;
            page.setScaleX(scale);
            page.setScaleY(scale);
            page.setAlpha(1f - DEPTH_FADE * a);
            if (adapter != null && keys[k] != NONE) adapter.onOffset(page, off);
        }
        if (listener != null && keys[1] != NONE) {
            float off = h > 0 ? -drag / h : 0f; // > 0 while moving toward the next page
            long to = off > 0 ? keys[2] : (off < 0 ? keys[0] : NONE);
            listener.onScroll(keys[1], to, Math.min(1f, Math.abs(off)), travel + off);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (anim != null) anim.cancel();
        drag = 0;
        layoutPages();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            if (velocity == null) velocity = VelocityTracker.obtain();
            else velocity.clear();
        }
        if (velocity != null) velocity.addMovement(e);
        return super.dispatchTouchEvent(e);
    }

    /** If a page is still settling, jump to where it was going so the next swipe starts fresh. */
    private boolean finishSettle() {
        if (anim != null && anim.isRunning()) {
            anim.end();
            return true;
        }
        return false;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = lastY = e.getY();
                dragging = false;
                if (finishSettle()) {
                    dragging = true; // swiping again mid-animation chains straight on
                    return true;
                }
                return false;
            case MotionEvent.ACTION_MOVE:
                float dy = e.getY() - downY;
                float dx = e.getX() - downX;
                if (Math.abs(dy) > touchSlop && Math.abs(dy) > Math.abs(dx)) {
                    dragging = true;
                    lastY = e.getY();
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (adapter == null) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = lastY = e.getY();
                if (finishSettle()) dragging = true;
                return true;
            case MotionEvent.ACTION_MOVE: {
                float y = e.getY();
                if (!dragging && Math.abs(y - downY) > touchSlop) {
                    dragging = true;
                    lastY = y;
                }
                if (dragging) {
                    applyDrag(y - lastY);
                    lastY = y;
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                if (dragging) release();
                else performClick();
                dragging = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (dragging) release();
                dragging = false;
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void applyDrag(float dy) {
        float h = getHeight();
        if (h <= 0) return;
        float next = drag + dy;
        boolean atStart = keys[0] == NONE;
        boolean atEnd = keys[2] == NONE;
        if ((next > 0 && atStart) || (next < 0 && atEnd)) {
            next = drag + dy * 0.35f; // rubber band at the ends
            next = Math.max(-h * 0.2f, Math.min(h * 0.2f, next));
        }
        drag = Math.max(-h, Math.min(h, next));
        layoutPages();
    }

    private void release() {
        float h = getHeight();
        float vy = 0;
        if (velocity != null) {
            velocity.computeCurrentVelocity(1000, maxFling);
            vy = velocity.getYVelocity();
        }
        int dir = 0;
        if (Math.abs(vy) > flingThreshold) dir = vy < 0 ? 1 : -1; // flick up = next
        else if (drag < -h * DISTANCE_THRESHOLD) dir = 1;
        else if (drag > h * DISTANCE_THRESHOLD) dir = -1;
        if (dir == 1 && drag > 0) dir = 0;
        if (dir == -1 && drag < 0) dir = 0;
        if (dir == 1 && keys[2] == NONE) dir = 0;
        if (dir == -1 && keys[0] == NONE) dir = 0;
        settle(dir, dir == 0 ? 0 : Math.abs(vy));
    }

    /**
     * Animate to the next (1), previous (-1) or back to the current (0) page. {@code speed} is the
     * finger's speed in px/s; the animation starts at that speed so a flick carries straight on.
     */
    private void settle(final int dir, float speed) {
        float h = getHeight();
        if (anim != null) anim.cancel();
        final float to = -dir * h;
        float dist = Math.abs(to - drag);
        if (h <= 0 || dist < 1f) {
            drag = to;
            if (dir != 0) commit(dir);
            else layoutPages();
            return;
        }
        long ms;
        if (speed > 0) ms = (long) (2f * DECELERATE * dist / speed * 1000f); // match initial speed
        else ms = (long) (110 + 90 * (dist / h));
        ms = Math.max(MIN_SETTLE_MS, Math.min(MAX_SETTLE_MS, ms));
        anim = ValueAnimator.ofFloat(drag, to);
        anim.setDuration(ms);
        anim.setInterpolator(new DecelerateInterpolator(DECELERATE));
        anim.addUpdateListener(a -> {
            drag = (float) a.getAnimatedValue();
            layoutPages();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            private boolean canceled;

            @Override
            public void onAnimationCancel(Animator a) {
                canceled = true;
            }

            @Override
            public void onAnimationEnd(Animator a) {
                if (!canceled && dir != 0) commit(dir);
            }
        });
        anim.start();
    }

    private void commit(int dir) {
        if (dir > 0) {
            View recycled = pages[0];
            pages[0] = pages[1];
            pages[1] = pages[2];
            pages[2] = recycled;
            keys[0] = keys[1];
            keys[1] = keys[2];
            keys[2] = adapter.next(keys[1]);
            travel++;
            drag = 0;
            bindSlot(2);
        } else {
            View recycled = pages[2];
            pages[2] = pages[1];
            pages[1] = pages[0];
            pages[0] = recycled;
            keys[2] = keys[1];
            keys[1] = keys[0];
            keys[0] = adapter.prev(keys[1]);
            travel--;
            drag = 0;
            bindSlot(0);
        }
        layoutPages();
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); // light tick per page
        if (listener != null) listener.onPageChanged(keys[1]);
    }

    /** Mouse wheel / trackpad support (Chromebooks, emulators). */
    @Override
    public boolean onGenericMotionEvent(MotionEvent e) {
        if (adapter != null && e.isFromSource(InputDevice.SOURCE_CLASS_POINTER)
                && e.getAction() == MotionEvent.ACTION_SCROLL) {
            float v = e.getAxisValue(MotionEvent.AXIS_VSCROLL);
            int dir = v < 0 ? 1 : (v > 0 ? -1 : 0);
            if (dir != 0) {
                finishSettle();
                if (dir == 1 && keys[2] == NONE) dir = 0;
                if (dir == -1 && keys[0] == NONE) dir = 0;
                if (dir != 0) {
                    settle(dir, 0);
                    return true;
                }
            }
        }
        return super.onGenericMotionEvent(e);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (anim != null) anim.cancel();
        if (velocity != null) {
            velocity.recycle();
            velocity = null;
        }
    }
}
