package com.versefeed;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.ScrollView;

/**
 * A ScrollView you can pull past its bottom: once the content can't scroll any further, dragging
 * up lifts it (with resistance) and reports progress; letting go far enough triggers
 * {@link Listener#onPull()}, otherwise it springs back.
 */
final class PullScroll extends ScrollView {

    interface Listener {
        /** 0..1 as the pull approaches the trigger distance (may exceed 1). */
        void onProgress(float progress);

        void onPull();
    }

    private static final float RESISTANCE = 0.45f;

    private final float threshold;
    private final int slop;
    private Listener listener;
    private float downY, lastY, pullStartY, lift;
    private boolean pulling;

    PullScroll(Context c) {
        super(c);
        threshold = 80 * c.getResources().getDisplayMetrics().density;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        setOverScrollMode(OVER_SCROLL_NEVER);
    }

    void setListener(Listener l) {
        listener = l;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = lastY = e.getY();
                pulling = false;
                lift = 0;
                break;
            case MotionEvent.ACTION_MOVE: {
                float y = e.getY();
                if (!pulling && y < lastY && downY - y > slop && !canScrollVertically(1)) {
                    // At the bottom and still dragging up: take over from the scroll view/buttons.
                    pulling = true;
                    pullStartY = y;
                    MotionEvent cancel = MotionEvent.obtain(e);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancel);
                    cancel.recycle();
                }
                lastY = y;
                if (pulling) {
                    lift = Math.max(0f, pullStartY - y) * RESISTANCE;
                    View child = getChildAt(0);
                    if (child != null) child.setTranslationY(-lift);
                    if (listener != null) listener.onProgress(lift / threshold);
                    return true;
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (pulling) {
                    pulling = false;
                    boolean fire = e.getActionMasked() == MotionEvent.ACTION_UP && lift >= threshold;
                    if (fire && listener != null) {
                        listener.onPull();
                    } else {
                        View child = getChildAt(0);
                        if (child != null) {
                            child.animate().translationY(0f).setDuration(220)
                                    .setInterpolator(new DecelerateInterpolator(1.6f)).start();
                        }
                        if (listener != null) listener.onProgress(0f);
                    }
                    return true;
                }
                break;
            default:
                break;
        }
        return super.dispatchTouchEvent(e);
    }
}
