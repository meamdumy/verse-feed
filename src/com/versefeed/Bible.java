package com.versefeed;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

/**
 * One whole Bible translation, loaded from assets/&lt;id&gt;.txt ("GEN 1:1 text" per line).
 * Only one translation is kept in memory at a time.
 */
final class Bible {

    /** Asset file names (without .txt), abbreviations and full names, by translation number. */
    static final String[] TRANSLATION_IDS = {"web", "bsb"};
    static final String[] TRANSLATION_ABBR = {"WEB", "BSB"};
    static final String[] TRANSLATION_TITLES = {"World English Bible", "Berean Standard Bible"};

    static final String[] NAMES = {
            "Genesis", "Exodus", "Leviticus", "Numbers", "Deuteronomy", "Joshua", "Judges", "Ruth",
            "1 Samuel", "2 Samuel", "1 Kings", "2 Kings", "1 Chronicles", "2 Chronicles", "Ezra",
            "Nehemiah", "Esther", "Job", "Psalms", "Proverbs", "Ecclesiastes", "Song of Solomon",
            "Isaiah", "Jeremiah", "Lamentations", "Ezekiel", "Daniel", "Hosea", "Joel", "Amos",
            "Obadiah", "Jonah", "Micah", "Nahum", "Habakkuk", "Zephaniah", "Haggai", "Zechariah",
            "Malachi",
            "Matthew", "Mark", "Luke", "John", "Acts", "Romans", "1 Corinthians", "2 Corinthians",
            "Galatians", "Ephesians", "Philippians", "Colossians", "1 Thessalonians",
            "2 Thessalonians", "1 Timothy", "2 Timothy", "Titus", "Philemon", "Hebrews", "James",
            "1 Peter", "2 Peter", "1 John", "2 John", "3 John", "Jude", "Revelation"
    };
    static final int OLD_TESTAMENT_BOOKS = 39;
    private static final int PSALMS = 18;

    final int translation;
    final String abbr;
    final String title;
    final int count;
    final byte[] book;
    final short[] chapter;
    final short[] verse;
    final String[] text;
    /** chapterStart[book][chapter - 1] = index of the first verse of that chapter. */
    final int[][] chapterStart;
    /** Verse indices for the verse of the day, in rotation order. */
    final int[] daily;

    private static Bible instance;

    static synchronized Bible get(Context ctx, int translation) {
        if (translation < 0 || translation >= TRANSLATION_IDS.length) translation = 0;
        if (instance == null || instance.translation != translation) {
            instance = null; // let the previous translation be freed before loading the next
            try {
                instance = new Bible(ctx.getApplicationContext(), translation);
            } catch (IOException e) {
                throw new RuntimeException("Could not load the Bible text", e);
            }
        }
        return instance;
    }

    private Bible(Context ctx, int translation) throws IOException {
        this.translation = translation;
        this.abbr = TRANSLATION_ABBR[translation];
        this.title = TRANSLATION_TITLES[translation];
        int cap = 31200;
        byte[] bk = new byte[cap];
        short[] chs = new short[cap];
        short[] vs = new short[cap];
        ArrayList<String> texts = new ArrayList<>(cap);
        HashMap<String, Integer> codes = new HashMap<>();

        int n = 0;
        int b = -1;
        String prevCode = null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                ctx.getAssets().open(TRANSLATION_IDS[translation] + ".txt"), StandardCharsets.UTF_8), 1 << 16)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isEmpty() && line.charAt(0) == '﻿') line = line.substring(1);
                int s1 = line.indexOf(' ');
                if (s1 <= 0) continue;
                int colon = line.indexOf(':', s1 + 1);
                if (colon < 0) continue;
                // The verse number is the run of digits after the colon; anything glued to it
                // (e.g. "EPH 3:14...") belongs to the text.
                int s2 = colon + 1;
                while (s2 < line.length() && Character.isDigit(line.charAt(s2))) s2++;
                if (s2 == colon + 1 || s2 - colon > 4 || colon - s1 < 2 || colon - s1 > 4) continue;
                String code = line.substring(0, s1);
                if (!code.equals(prevCode)) {
                    b++;
                    prevCode = code;
                    codes.put(code, b);
                }
                String t = line.substring(s2).trim();
                if (t.isEmpty()) continue; // verses the WEB leaves blank (e.g. Luke 17:36)
                if (n == bk.length) {
                    int grown = bk.length + 1024;
                    bk = Arrays.copyOf(bk, grown);
                    chs = Arrays.copyOf(chs, grown);
                    vs = Arrays.copyOf(vs, grown);
                }
                bk[n] = (byte) b;
                try {
                    chs[n] = Short.parseShort(line.substring(s1 + 1, colon));
                    vs[n] = Short.parseShort(line.substring(colon + 1, s2));
                } catch (NumberFormatException bad) {
                    continue; // skip a malformed line rather than fail to open the Bible
                }
                texts.add(t);
                n++;
            }
        }
        if (b + 1 != NAMES.length) throw new IOException("Expected 66 books, found " + (b + 1));

        count = n;
        book = Arrays.copyOf(bk, n);
        chapter = Arrays.copyOf(chs, n);
        verse = Arrays.copyOf(vs, n);
        text = texts.toArray(new String[0]);

        int[] maxChapter = new int[NAMES.length];
        for (int i = 0; i < n; i++) maxChapter[book[i]] = Math.max(maxChapter[book[i]], chapter[i]);
        chapterStart = new int[NAMES.length][];
        for (int i = 0; i < NAMES.length; i++) {
            chapterStart[i] = new int[maxChapter[i]];
            Arrays.fill(chapterStart[i], -1);
        }
        for (int i = 0; i < n; i++) {
            int[] cs = chapterStart[book[i]];
            if (cs[chapter[i] - 1] < 0) cs[chapter[i] - 1] = i;
        }

        daily = loadDaily(ctx, codes);
    }

    private int[] loadDaily(Context ctx, HashMap<String, Integer> codes) throws IOException {
        ArrayList<Integer> found = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                ctx.getAssets().open("daily.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                int s = line.indexOf(' ');
                int c = line.indexOf(':');
                if (line.isEmpty() || line.startsWith("#") || s < 0 || c < s) continue;
                Integer bk = codes.get(line.substring(0, s));
                if (bk == null) continue;
                try {
                    int idx = indexOf(bk, Integer.parseInt(line.substring(s + 1, c)),
                            Integer.parseInt(line.substring(c + 1)));
                    if (idx >= 0) found.add(idx);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (found.isEmpty()) found.add(0);
        int[] out = new int[found.size()];
        for (int i = 0; i < out.length; i++) out[i] = found.get(i);
        return out;
    }

    int chapterCount(int b) {
        return chapterStart[b].length;
    }

    /** Index just past the last verse of the chapter. */
    int chapterEnd(int b, int ch) {
        if (ch < chapterStart[b].length) return chapterStart[b][ch];
        if (b + 1 < NAMES.length) return chapterStart[b + 1][0];
        return count;
    }

    int lastVerseNumber(int b, int ch) {
        return verse[chapterEnd(b, ch) - 1];
    }

    int indexOf(int b, int ch, int v) {
        if (b < 0 || b >= NAMES.length || ch < 1 || ch > chapterStart[b].length) return -1;
        int end = chapterEnd(b, ch);
        for (int i = chapterStart[b][ch - 1]; i < end; i++) if (verse[i] == v) return i;
        return -1;
    }

    /**
     * Index of book {@code b} chapter {@code ch} verse {@code v}, or of the next verse that exists
     * (translations differ on a few verses, e.g. the BSB omits Luke 17:36). Never returns -1.
     */
    int indexAtOrAfter(int b, int ch, int v) {
        if (b < 0 || b >= NAMES.length) return 0;
        ch = Math.max(1, Math.min(ch, chapterStart[b].length));
        int end = chapterEnd(b, ch);
        for (int i = chapterStart[b][ch - 1]; i < end; i++) if (verse[i] >= v) return i;
        return Math.min(end, count - 1);
    }

    private String[] searchText; // normalized copies of the verses, built on first search

    /**
     * Finds verses containing {@code needle} (already normalized with
     * {@link RefSearch#normalizeText}). Fills {@code out} with the first matches in Bible order
     * and returns how many verses match in total. Safe to call off the UI thread.
     */
    int search(String needle, int[] out) {
        String[] t;
        synchronized (this) {
            if (searchText == null) {
                String[] s = new String[count];
                for (int i = 0; i < count; i++) s[i] = RefSearch.normalizeText(text[i]);
                searchText = s;
            }
            t = searchText;
        }
        int total = 0;
        for (int i = 0; i < count; i++) {
            if (t[i].contains(needle)) {
                if (total < out.length) out[total] = i;
                total++;
            }
        }
        return total;
    }

    String ref(int i) {
        String name = book[i] == PSALMS ? "Psalm" : NAMES[book[i]];
        return name + " " + chapter[i] + ":" + verse[i];
    }
}
