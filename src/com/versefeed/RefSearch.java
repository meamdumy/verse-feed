package com.versefeed;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns what you type into Bible references, the way YouVersion's search does:
 * "john", "jn 3", "1 cor 13:4", "ps 23.1", "song 2", "jude 5". Book names match by prefix
 * (so "gen", "phil", "rev" work) plus common abbreviations that aren't prefixes ("jn", "mk").
 */
final class RefSearch {

    static final int KIND_BOOK = 0;
    static final int KIND_CHAPTER = 1;
    static final int KIND_VERSE = 2;

    static final class Hit {
        final int kind, book, index;
        final String title, subtitle;

        Hit(int kind, int book, int index, String title, String subtitle) {
            this.kind = kind;
            this.book = book;
            this.index = index;
            this.title = title;
            this.subtitle = subtitle;
        }
    }

    /** Abbreviations that are not simply the start of the book's name. */
    private static final String[][] ALIASES = {
            {"gn", "genesis"}, {"lv", "leviticus"}, {"nm", "numbers"}, {"dt", "deuteronomy"},
            {"jdg", "judges"}, {"jgs", "judges"}, {"rth", "ruth"}, {"sm", "samuel"}, {"sa", "samuel"},
            {"kgs", "kings"}, {"ki", "kings"}, {"chr", "chronicles"}, {"ch", "chronicles"},
            {"jb", "job"}, {"ps", "psalms"}, {"psa", "psalms"}, {"pss", "psalms"}, {"psm", "psalms"},
            {"pr", "proverbs"}, {"prv", "proverbs"}, {"qoh", "ecclesiastes"},
            {"sos", "songofsolomon"}, {"sng", "songofsolomon"}, {"canticles", "songofsolomon"},
            {"songofsongs", "songofsolomon"}, {"dn", "daniel"}, {"jl", "joel"}, {"ob", "obadiah"},
            {"zp", "zephaniah"}, {"zc", "zechariah"}, {"mt", "matthew"}, {"mtt", "matthew"},
            {"mk", "mark"}, {"mrk", "mark"}, {"lk", "luke"}, {"jn", "john"}, {"jhn", "john"},
            {"rm", "romans"}, {"co", "corinthians"}, {"th", "thessalonians"}, {"ti", "timothy"},
            {"tm", "timothy"}, {"php", "philippians"}, {"phm", "philemon"}, {"hb", "hebrews"},
            {"jas", "james"}, {"jm", "james"}, {"pe", "peter"}, {"pt", "peter"}, {"jd", "jude"},
            {"rv", "revelation"},
    };
    private static final HashMap<String, String> ALIAS = new HashMap<>();
    private static final String[] KEYS = new String[Bible.NAMES.length];

    static {
        for (String[] a : ALIASES) ALIAS.put(a[0], a[1]);
        for (int i = 0; i < KEYS.length; i++) KEYS[i] = squash(Bible.NAMES[i]);
    }

    // [1-3] book-name [chapter [ :|.|,|space verse]]
    private static final Pattern REF = Pattern.compile(
            "^([1-3])?\\s*([a-z][a-z .]*?)\\.?\\s*(?:(\\d{1,3})(?:\\s*[:.,\\s]\\s*(\\d{1,3}))?)?$");

    /** Lower-case text with curly quotes made straight; keeps the same length as the input. */
    static String normalizeText(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replace('’', '\'').replace('‘', '\'')
                .replace('“', '"').replace('”', '"');
    }

    static int letterCount(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (Character.isLetter(s.charAt(i))) n++;
        return n;
    }

    private static String squash(String s) {
        return s.toLowerCase(Locale.ROOT).replace(" ", "").replace(".", "");
    }

    /** Books whose name matches the typed number + name, best matches first. */
    static List<Integer> matchBooks(String num, String name) {
        String rest = squash(name);
        ArrayList<Integer> exact = new ArrayList<>(), prefix = new ArrayList<>();
        if (rest.isEmpty()) return exact;
        String alias = ALIAS.get(rest);
        for (int b = 0; b < KEYS.length; b++) {
            String key = KEYS[b];
            boolean numbered = Character.isDigit(key.charAt(0));
            String bare = numbered ? key.substring(1) : key;
            if (num != null && (!numbered || key.charAt(0) != num.charAt(0))) continue;
            if (bare.equals(rest) || (alias != null && bare.equals(alias))) exact.add(b);
            else if (bare.startsWith(rest)) prefix.add(b);
        }
        exact.addAll(prefix);
        return exact;
    }

    static List<Hit> references(Bible bible, String query) {
        ArrayList<Hit> hits = new ArrayList<>();
        Matcher m = REF.matcher(query.toLowerCase(Locale.ROOT).trim());
        if (!m.matches()) return hits;
        String num = m.group(1);
        Integer ch = m.group(3) == null ? null : Integer.valueOf(m.group(3));
        Integer v = m.group(4) == null ? null : Integer.valueOf(m.group(4));
        List<Integer> books = matchBooks(num, m.group(2));
        int limit = ch == null ? 8 : 5;
        for (int i = 0; i < books.size() && hits.size() < limit; i++) {
            int b = books.get(i);
            String name = b == 18 ? "Psalm" : Bible.NAMES[b];
            int chapters = bible.chapterCount(b);
            if (ch == null) {
                hits.add(new Hit(KIND_BOOK, b, -1, Bible.NAMES[b],
                        chapters == 1 ? "1 chapter" : chapters + " chapters"));
                continue;
            }
            int c = ch, vs = v == null ? -1 : v;
            if (chapters == 1 && v == null) { // "jude 5" means verse 5
                vs = c;
                c = 1;
            }
            if (c < 1 || c > chapters) continue;
            if (vs < 0) {
                int first = bible.chapterStart[b][c - 1];
                hits.add(new Hit(KIND_CHAPTER, b, first, name + " " + c, bible.text[first]));
            } else {
                int idx = bible.indexOf(b, c, vs);
                if (idx < 0) continue;
                String title = chapters == 1 ? Bible.NAMES[b] + " " + vs : name + " " + c + ":" + vs;
                hits.add(new Hit(KIND_VERSE, b, idx, title, bible.text[idx]));
            }
        }
        return hits;
    }
}
