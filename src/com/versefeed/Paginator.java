package com.versefeed;

import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

/**
 * Splits verses into pages of at most {@link #MAX_LINES} lines, using the same paint and width
 * as the on-screen verse text. Line breaking is greedy (BREAK_STRATEGY_SIMPLE, no hyphenation),
 * so a chunk re-laid out on its own breaks at exactly the same places.
 */
final class Paginator {

    static final int MAX_LINES = 6;

    private final String[] text;
    private final TextPaint paint;
    private final int width;
    private final byte[] parts; // 0 = not measured yet

    Paginator(String[] text, TextPaint paint, int width) {
        this.text = text;
        this.paint = paint;
        this.width = Math.max(1, width);
        this.parts = new byte[text.length];
    }

    /** Number of pages verse {@code v} needs (1..255). */
    int parts(int v) {
        int p = parts[v] & 0xFF;
        if (p == 0) {
            int lines = layout(text[v]).getLineCount();
            p = Math.max(1, Math.min(255, (lines + MAX_LINES - 1) / MAX_LINES));
            parts[v] = (byte) p;
        }
        return p;
    }

    /** The text of page {@code part} of verse {@code v}. */
    String chunk(int v, int part) {
        String s = text[v];
        StaticLayout l = layout(s);
        int lines = l.getLineCount();
        int first = part * MAX_LINES;
        if (first >= lines) return s;
        int last = Math.min(lines, first + MAX_LINES) - 1;
        return s.substring(l.getLineStart(first), l.getLineEnd(last)).trim();
    }

    private StaticLayout layout(String s) {
        return StaticLayout.Builder.obtain(s, 0, s.length(), paint, width)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build();
    }
}
