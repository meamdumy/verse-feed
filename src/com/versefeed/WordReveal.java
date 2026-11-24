package com.versefeed;

import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.CharacterStyle;
import android.text.style.UpdateAppearance;

import java.util.ArrayList;

/**
 * Verse text whose words fade in one after another, in reading order. {@link #set} takes a
 * progress from 0 (all hidden) to 1 (all shown); the feed drives it from the swipe position,
 * so words pour in as a page arrives and drain out as it leaves. Only alpha changes, so line
 * breaks never move.
 */
final class WordReveal {

    /** How much of the progress range is spent staggering word starts. */
    private static final float SPREAD = 0.45f;

    private static final class Fade extends CharacterStyle implements UpdateAppearance {
        float alpha = 1f;

        @Override
        public void updateDrawState(TextPaint tp) {
            tp.setAlpha(Math.round(tp.getAlpha() * alpha));
        }
    }

    final SpannableString text;
    private final Fade[] fades;
    private float last = -1f;

    WordReveal(String s) {
        text = new SpannableString(s);
        ArrayList<Fade> list = new ArrayList<>();
        int i = 0, n = s.length();
        while (i < n) {
            while (i < n && Character.isWhitespace(s.charAt(i))) i++;
            int start = i;
            while (i < n && !Character.isWhitespace(s.charAt(i))) i++;
            if (i > start) {
                Fade f = new Fade();
                text.setSpan(f, start, i, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                list.add(f);
            }
        }
        fades = list.toArray(new Fade[0]);
    }

    /** Returns true if anything changed (the caller should then invalidate the view). */
    boolean set(float progress) {
        float r = Math.max(0f, Math.min(1f, progress));
        if (Math.abs(r - last) < 0.002f) return false;
        last = r;
        int n = fades.length;
        for (int i = 0; i < n; i++) {
            float start = n > 1 ? SPREAD * i / (n - 1) : 0f;
            float a = (r - start) / (1f - SPREAD);
            fades[i].alpha = Math.max(0f, Math.min(1f, a));
        }
        return true;
    }
}
