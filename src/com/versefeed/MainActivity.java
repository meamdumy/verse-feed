package com.versefeed;

import android.animation.Animator;
import android.animation.ArgbEvaluator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.window.OnBackInvokedDispatcher;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.function.IntConsumer;

public final class MainActivity extends Activity {

    private static final int SCREEN_HOME = 0;
    private static final int SCREEN_FEED = 1;
    private static final int SCREEN_BOOKS = 2;
    private static final int SCREEN_CHAPTERS = 3;
    private static final int SCREEN_VERSES = 4;

    private static final int MODE_READ = 0;
    private static final int MODE_SHUFFLE = 1;
    private static final int MODE_DAILY = 2; // today's verse in context (part of Home; own position)

    private static final int BG_COLORFUL = 0;
    private static final int BG_BLUR_LIGHT = 1;
    private static final int BG_BLUR_DARK = 2;


    private static final float VERSE_SP = 24f;
    private static final int BOX_MARGIN_DP = 18;
    private static final int BOX_PAD_DP = 22;

    private static final int[][] FEED_GRADIENTS = {
            {0xFF1E1B4B, 0xFF4338CA}, {0xFF0F172A, 0xFF0E7490}, {0xFF052E16, 0xFF15803D},
            {0xFF3B0764, 0xFF9333EA}, {0xFF431407, 0xFFB8400B}, {0xFF172554, 0xFF2563EB},
            {0xFF4C0519, 0xFFBE123C}, {0xFF1C1917, 0xFF57534E}, {0xFF042F2E, 0xFF0F766E},
            {0xFF3A1D04, 0xFF95590A},
    };

    private FrameLayout root;
    private Bible bible;
    private SharedPreferences prefs;
    private Bundle pendingState;

    private int screen = SCREEN_HOME;
    private int feedMode = MODE_READ;
    private int browseBook = -1;
    private int browseReturn = SCREEN_HOME;
    private int[] shuffleOrder;

    // Screen transitions
    private static final int SCREEN_LOADING = -1;
    private static final int NAV_NONE = 2; // navDir value: change screens without animating
    private static final Object BAR_TAG = new Object();
    private int shownScreen = SCREEN_LOADING;
    private int navDir = 1; // 1 = forward (slide in from the right), -1 = back, 0 = crossfade
    private boolean navKeepBar; // Read/Shuffle switch: the top bar stays put
    private boolean navVertical; // Home <-> today's verse: slide up/down instead of sideways

    // Bottom bar
    private FrameLayout navBar;
    private final ImageView[] navIcons = new ImageView[4];
    private View navHairline, navIndicator;
    private int navActive = -1;
    private int navBg;
    private int navBottomInset;
    private FrameLayout outgoing;
    private int cascadeIndex;

    // Menu theme: colours come from these resources, swapped in place when the theme changes
    private Resources themed;
    private ScrollView homeScroll;
    private int pendingHomeScroll;
    private ImageView coverView; // picture of the old look that blurs away during a theme switch
    private Bitmap coverShot;

    // Settings card (lives in the window's decor view so it survives screen rebuilds)
    private FrameLayout settingsPanel;
    private ImageView gearButton;
    private TextView translationStatus;
    private int translationGen;

    // Search
    private int searchGen;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // The window's top-level layout and the background shared by every screen
    private FrameLayout appFrame;
    private FeedBackground background;

    // Feed state (rebuilt by showFeed)
    private TextView counter;
    private Paginator paginator;
    private int feedWidth;
    private int fText, fSub, fBoxFill, fBoxStroke, fPill;
    private boolean fLightBars;

    private int insetTop, insetBottom;
    private final List<InsetTarget> insetTargets = new ArrayList<>();

    private static final class InsetTarget {
        final View view;
        final int baseTop, baseBottom;
        final boolean top, bottom;

        InsetTarget(View v, boolean top, boolean bottom) {
            this.view = v;
            this.baseTop = v.getPaddingTop();
            this.baseBottom = v.getPaddingBottom();
            this.top = top;
            this.bottom = bottom;
        }
    }

    private static final class PageHolder {
        TextView ref, verse, dots, context, share;
        View box, actions;
        WordReveal reveal;
        ValueAnimator intro;
        float shown = -1f;
    }

    // Arrival choreography, as fractions of a page's arrival (0 = off screen, 1 = settled):
    // the box comes in first, the words start only once it has landed, the buttons come last.
    private static final float BOX_END = 0.30f;
    private static final float WORDS_START = 0.30f; // = BOX_END: words wait for the box to land
    private static final float WORDS_END = 0.85f; // text is fully readable before the page settles
    private static final float BUTTONS_START = 0.55f;
    private static final float BUTTONS_END = 0.90f;
    private static final long INTRO_MS = 550;

    // ------------------------------------------------------------------ lifecycle

    /**
     * The background choice themes the whole app: "Blur light" gets the light UI; "Colorful" and
     * "Blur dark" get the dark UI. Applied by overriding the night-mode bits of the configuration.
     */
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        int bgStyle = base.getSharedPreferences("state", MODE_PRIVATE).getInt("bg_style", BG_COLORFUL);
        Configuration c = new Configuration();
        c.uiMode = (base.getResources().getConfiguration().uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | nightFor(bgStyle);
        applyOverrideConfiguration(c);
    }

    private static int nightFor(int bgStyle) {
        return bgStyle == BG_BLUR_LIGHT ? Configuration.UI_MODE_NIGHT_NO : Configuration.UI_MODE_NIGHT_YES;
    }

    /** The shared backdrop behind every screen, in the chosen style. */
    private FeedBackground makeBackground() {
        int bgStyle = prefs.getInt("bg_style", BG_COLORFUL);
        return new FeedBackground(this,
                bgStyle == BG_COLORFUL ? FeedBackground.STYLE_COLOR : FeedBackground.STYLE_BUBBLES,
                bgStyle == BG_BLUR_DARK);
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        themed = getResources(); // already in the chosen theme (see attachBaseContext)
        prefs = getSharedPreferences("state", MODE_PRIVATE);
        pendingState = saved;
        setupWindow();

        root = new FrameLayout(this); // screens go here, over the shared background
        FrameLayout frame = new FrameLayout(this);
        appFrame = frame;
        background = makeBackground();
        frame.addView(background, match());
        frame.addView(root, match());
        buildNavBar(frame);
        frame.setOnApplyWindowInsetsListener((v, insets) -> {
            int nav, ime;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                insetTop = i.top;
                nav = i.bottom;
                ime = insets.getInsets(WindowInsets.Type.ime()).bottom;
            } else {
                insetTop = insets.getSystemWindowInsetTop();
                nav = insets.getStableInsetBottom();
                ime = Math.max(0, insets.getSystemWindowInsetBottom() - nav);
            }
            boolean keyboard = ime > nav;
            showNavBar(!keyboard);
            navBottomInset = nav;
            navBar.setPadding(0, 0, 0, nav);
            // Content clears the bottom bar, or the keyboard so search results scroll above it.
            insetBottom = keyboard ? ime : nav + navBarHeight();
            for (InsetTarget t : insetTargets) applyInset(t);
            return insets;
        });
        // Re-paginate the feed when the width changes (rotation, split screen).
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (screen == SCREEN_FEED && bible != null && r - l > 0 && r - l != feedWidth) {
                root.post(() -> {
                    if (screen == SCREEN_FEED && root.getWidth() != feedWidth) {
                        navDir = NAV_NONE;
                        showFeed(feedMode, -1);
                    }
                });
            }
        });
        setContentView(frame);
        registerBack();
        showLoading();

        new Thread(() -> {
            Bible b = loadBible(prefs.getInt("translation", 0));
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                bible = b;
                restore(pendingState);
                pendingState = null;
            });
        }, "bible-loader").start();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (bible == null) {
            if (pendingState != null) out.putAll(pendingState);
            return;
        }
        out.putInt("screen", screen);
        out.putInt("feedMode", feedMode);
        out.putInt("browseBook", browseBook);
        out.putInt("browseReturn", browseReturn);
    }

    private void restore(Bundle s) {
        if (s == null || !s.containsKey("screen")) {
            showHome();
            return;
        }
        feedMode = s.getInt("feedMode", MODE_READ);
        browseBook = s.getInt("browseBook", -1);
        browseReturn = s.getInt("browseReturn", SCREEN_HOME);
        int sc = s.getInt("screen");
        int verseBook = prefs.getInt("browse_verses_book", -1);
        int verseChapter = prefs.getInt("browse_verses_chapter", -1);
        if (sc == SCREEN_FEED) showFeed(feedMode, -1);
        else if (sc == SCREEN_BOOKS) showBooks();
        else if (sc == SCREEN_CHAPTERS && browseBook >= 0) showChapters(browseBook);
        else if (sc == SCREEN_VERSES && verseBook >= 0 && verseChapter > 0) showVerses(verseBook, verseChapter);
        else showHome();
    }

    private void registerBack() {
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() { // Android 12L and older
        handleBack();
    }

    private void handleBack() {
        if (bible == null) {
            finish();
            return;
        }
        if (settingsPanel != null) {
            closeSettings();
            return;
        }
        navDir = -1;
        switch (screen) {
            case SCREEN_FEED:
                if (feedMode == MODE_DAILY) leaveDaily(true);
                else showHome();
                break;
            case SCREEN_BOOKS:
                if (browseReturn == SCREEN_FEED) showFeed(feedMode, -1);
                else showHome();
                break;
            case SCREEN_CHAPTERS:
                showBooks();
                break;
            case SCREEN_VERSES:
                if (bible.chapterCount(browseBook) == 1) showBooks();
                else showChapters(browseBook);
                break;
            default:
                finish();
        }
    }

    // ------------------------------------------------------------------ window & insets

    @SuppressWarnings("deprecation")
    private void setupWindow() {
        Window w = getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
        } else {
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        if (Build.VERSION.SDK_INT >= 29) w.setNavigationBarContrastEnforced(false);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }
    }

    /** true = dark icons for a light background. */
    @SuppressWarnings("deprecation")
    private void setLightBars(boolean light) {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(light ? mask : 0, mask);
            }
        } else {
            View d = getWindow().getDecorView();
            int mask = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            int flags = d.getSystemUiVisibility();
            d.setSystemUiVisibility(light ? (flags | mask) : (flags & ~mask));
        }
    }

    private boolean isNight() {
        return (themed.getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private void insets(View v, boolean top, boolean bottom) {
        InsetTarget t = new InsetTarget(v, top, bottom);
        insetTargets.add(t);
        applyInset(t);
    }

    private void applyInset(InsetTarget t) {
        t.view.setPadding(t.view.getPaddingLeft(),
                t.baseTop + (t.top ? insetTop : 0),
                t.view.getPaddingRight(),
                t.baseBottom + (t.bottom ? insetBottom : 0));
    }

    private static boolean transitionsOn() {
        return ValueAnimator.areAnimatorsEnabled();
    }

    /**
     * Empties the screen for the next one. If the change should animate, the old screen's views
     * are kept in a layer that {@link #runTransition} slides away underneath the new screen.
     */
    private void clearScreen() {
        hideKeyboard();
        boolean animate = transitionsOn() && navDir != NAV_NONE
                && shownScreen != SCREEN_LOADING && screen != SCREEN_LOADING && root.getChildCount() > 0;
        shownScreen = screen;
        if (outgoing != null) {
            outgoing.animate().cancel();
            root.removeView(outgoing);
            outgoing = null;
        }
        if (animate) {
            ArrayList<View> kids = new ArrayList<>();
            for (int i = 0; i < root.getChildCount(); i++) kids.add(root.getChildAt(i));
            root.removeAllViews();
            FrameLayout old = new FrameLayout(this);
            for (View k : kids) {
                k.animate().cancel();
                if (navKeepBar && k.getTag() == BAR_TAG) continue;
                old.addView(k, k.getLayoutParams());
            }
            outgoing = old;
        } else {
            root.removeAllViews();
            navDir = 1;
            navKeepBar = false;
            navVertical = false;
        }
        insetTargets.clear();
    }

    /** Call once a screen's views are in place: slides the new screen in over the old one. */
    private void runTransition() {
        updateNavBar();
        final FrameLayout old = outgoing;
        final int dir = navDir;
        final boolean keepBar = navKeepBar;
        final boolean vertical = navVertical;
        outgoing = null;
        navDir = 1;
        navKeepBar = false;
        navVertical = false;
        if (old == null) return;
        float span = vertical
                ? (root.getHeight() > 0 ? root.getHeight() : getResources().getDisplayMetrics().heightPixels)
                : (root.getWidth() > 0 ? root.getWidth() : getResources().getDisplayMetrics().widthPixels);
        // Screens are see-through (the shared background shows behind them), so the old and new
        // screen must never overlap. A "push": the new one slides fully in from one side while
        // the old one slides fully out of the other, in step, like pages on a strip.
        // (dir 0, e.g. after a translation change, is a fade-out-then-in instead.)
        final boolean fade = dir == 0;
        final long ms = fade ? 200 : 300;
        final PathInterpolator ease = new PathInterpolator(0.2f, 0f, 0f, 1f);
        for (int i = 0; i < root.getChildCount(); i++) {
            View v = root.getChildAt(i);
            if (keepBar && v.getTag() == BAR_TAG) continue;
            if (vertical) v.setTranslationY(dir * span);
            else v.setTranslationX(dir * span);
            v.setAlpha(fade ? 0f : 1f);
            v.animate().translationX(0f).translationY(0f).alpha(1f).setStartDelay(fade ? 120 : 0)
                    .setDuration(ms).setInterpolator(ease).start();
        }
        root.addView(old, 0, match()); // underneath the new screen
        outgoing = old;
        float outBy = -dir * span;
        (vertical ? old.animate().translationY(outBy) : old.animate().translationX(outBy))
                .alpha(fade ? 0f : 1f)
                .setDuration(fade ? 120 : ms)
                .setInterpolator(ease)
                .withEndAction(() -> {
                    root.removeView(old);
                    if (outgoing == old) outgoing = null;
                }).start();
    }

    /** Fades, lifts and pops a view in after {@code delay} ms (used for tiles and results). */
    private void popIn(View v, long delay) {
        if (!transitionsOn()) return;
        v.setAlpha(0f);
        v.setTranslationY(dp(12));
        v.setScaleX(0.94f);
        v.setScaleY(0.94f);
        v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setStartDelay(delay)
                .setDuration(200).setInterpolator(new DecelerateInterpolator(1.6f)).start();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && root != null) imm.hideSoftInputFromWindow(root.getWindowToken(), 0);
    }

    // ------------------------------------------------------------------ screens

    private void showLoading() {
        screen = SCREEN_LOADING;
        clearScreen();
        ProgressBar spinner = new ProgressBar(this);
        root.addView(spinner, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));
    }

    private void showHome() {
        screen = SCREEN_HOME;
        clearScreen();
        setLightBars(!isNight());

        PullScroll scroll = new PullScroll(this);
        scroll.setFillViewport(true);
        LinearLayout col = vertical();
        col.setPadding(dp(20), dp(16), dp(20), dp(16));
        scroll.addView(col);
        insets(col, true, true);

        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault()));
        TextView date = text(today.toUpperCase(Locale.getDefault()), 12, color(R.color.subtext));
        date.setLetterSpacing(0.08f);
        date.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(date, matchWrap(0, dp(8)));

        // Title with the settings gear at the top right.
        LinearLayout titleRow = horizontal();
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(getString(R.string.app_name), 32, color(R.color.text));
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        titleRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageView gear = new ImageView(this);
        gear.setImageResource(R.drawable.ic_settings);
        gear.setImageTintList(ColorStateList.valueOf(color(R.color.text)));
        gear.setPadding(dp(10), dp(10), dp(10), dp(10));
        gear.setBackground(ripple(color(R.color.surface), dp(22), true));
        gear.setContentDescription("Settings");
        gear.setOnClickListener(v -> {
            bump(v);
            openSettings(true);
        });
        gear.setRotation(settingsPanel != null ? 90f : 0f);
        titleRow.addView(gear, new LinearLayout.LayoutParams(dp(44), dp(44)));
        gearButton = gear;
        col.addView(titleRow, matchWrap(dp(2), dp(18)));

        // Verse of the day
        final int daily = dailyIndex();
        LinearLayout card = vertical();
        GradientDrawable cardBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{color(R.color.accent_a), color(R.color.accent_b)});
        cardBg.setCornerRadius(dp(24));
        card.setBackground(cardBg);
        card.setPadding(dp(22), dp(20), dp(22), dp(18));

        TextView label = text("VERSE OF THE DAY", 12, 0xCCFFFFFF);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setLetterSpacing(0.12f);
        card.addView(label, matchWrap(0, dp(12)));

        TextView verse = text(bible.text[daily], 21, Color.WHITE);
        verse.setTypeface(Typeface.SERIF);
        verse.setLineSpacing(0, 1.25f);
        card.addView(verse, matchWrap(0, dp(12)));

        TextView ref = text(bible.ref(daily), 15, Color.WHITE);
        ref.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(ref, matchWrap(0, dp(16)));

        LinearLayout buttons = horizontal();
        buttons.addView(pill("Read in context", 0x33FFFFFF, v -> openDaily()));
        View gap = new View(this);
        buttons.addView(gap, new LinearLayout.LayoutParams(dp(8), 1));
        buttons.addView(pill("Share", 0x33FFFFFF, v -> share(daily)));
        card.addView(buttons);
        col.addView(card, matchWrap(0, dp(20)));

        // Actions
        final int readPos = prefs.getInt("read_pos", -1);
        boolean started = readPos >= 0 && readPos < bible.count;
        col.addView(action(started ? "Continue reading" : "Start reading",
                started ? bible.ref(readPos) : "Genesis 1:1, one verse at a time",
                v -> showFeed(MODE_READ, started ? readPos : 0)), matchWrap(0, dp(10)));
        col.addView(action("Shuffle", "An endless feed of random verses",
                v -> showFeed(MODE_SHUFFLE, -1)), matchWrap(0, dp(10)));
        col.addView(action("Browse", "Jump to any book, chapter, or verse", v -> {
            browseReturn = SCREEN_HOME;
            showBooks();
        }), matchWrap(0, dp(10)));

        View spacer = new View(this);
        col.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        // At the bottom: keep swiping up to read today's verse in context (still part of Home).
        LinearLayout hint = vertical();
        hint.setGravity(Gravity.CENTER_HORIZONTAL);
        final TextView arrow = text("⌃", 22, color(R.color.accent_a));
        arrow.setGravity(Gravity.CENTER);
        hint.addView(arrow);
        final TextView hintText = text("Swipe up to read today’s verse in context", 13, color(R.color.subtext));
        hintText.setTypeface(Typeface.DEFAULT_BOLD);
        hintText.setGravity(Gravity.CENTER);
        hint.addView(hintText);
        col.addView(hint, matchWrap(dp(28), 0));
        if (transitionsOn()) { // a gentle bob so it reads as "swipe me"
            ObjectAnimator bob = ObjectAnimator.ofFloat(arrow, View.TRANSLATION_Y, 0f, -dp(6));
            bob.setDuration(900);
            bob.setRepeatMode(ValueAnimator.REVERSE);
            bob.setRepeatCount(ValueAnimator.INFINITE);
            bob.setInterpolator(new AccelerateDecelerateInterpolator());
            bob.start();
            arrow.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    bob.cancel();
                }
            });
        }
        scroll.setListener(new PullScroll.Listener() {
            @Override
            public void onProgress(float p) {
                float c = Math.min(1f, p);
                hint.setScaleX(1f + 0.08f * c);
                hint.setScaleY(1f + 0.08f * c);
                arrow.setRotation(0f);
                hintText.setText(p >= 1f ? "Release to open today’s verse"
                        : "Swipe up to read today’s verse in context");
                hintText.setTextColor(p >= 1f ? color(R.color.text) : color(R.color.subtext));
            }

            @Override
            public void onPull() {
                openDaily();
            }
        });

        TextView footer = text(bible.title + " (" + bible.abbr + ") · Public domain · eBible.org", 12,
                color(R.color.subtext));
        footer.setGravity(Gravity.CENTER);
        col.addView(footer, matchWrap(dp(14), 0));

        root.addView(scroll, match());
        addStatusScrim();
        background.animateTo(gradient(daily)); // Colorful: Home wears today's verse's colours
        homeScroll = scroll;
        if (pendingHomeScroll > 0) { // coming back from a theme switch: keep the same spot
            final int y = pendingHomeScroll;
            pendingHomeScroll = 0;
            scroll.post(() -> scroll.scrollTo(0, y));
        }
        runTransition();
    }

    /** A soft fade behind the status bar so scrolled content doesn't run under the clock. */
    private void addStatusScrim() {
        FrameLayout scrim = new FrameLayout(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{color(R.color.scrim_top), color(R.color.scrim_top) & 0x00FFFFFF}));
        scrim.setPadding(0, 0, 0, dp(14));
        insets(scrim, true, false);
        root.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
    }

    private void showFeed(int mode, int start) {
        screen = SCREEN_FEED;
        feedMode = mode;
        clearScreen();

        final int n = bible.count;
        // Each feed keeps its own place; today's-verse reading never touches "Continue reading".
        final String posKey = mode == MODE_READ ? "read_pos" : mode == MODE_SHUFFLE ? "shuffle_pos" : "daily_pos";
        final String partKey = mode == MODE_READ ? "read_part" : mode == MODE_SHUFFLE ? "shuffle_part" : "daily_part";
        if (mode == MODE_SHUFFLE) ensureShuffle();
        final int[] order = mode == MODE_SHUFFLE ? shuffleOrder : null;

        final int bgStyle = prefs.getInt("bg_style", BG_COLORFUL);
        applyFeedStyle(bgStyle);
        setLightBars(fLightBars);

        // Measure verses at the exact width the verse text will have inside its box.
        feedWidth = root.getWidth() > 0 ? root.getWidth() : getResources().getDisplayMetrics().widthPixels;
        int textWidth = feedWidth - 2 * dp(BOX_MARGIN_DP) - 2 * dp(BOX_PAD_DP);
        final Paginator pag = new Paginator(bible.text, new TextPaint(verseText().getPaint()), textWidth);
        paginator = pag;

        int seq = start >= 0 ? start : prefs.getInt(posKey, 0);
        seq = Math.max(0, Math.min(n - 1, seq));
        int part = start >= 0 ? 0 : prefs.getInt(partKey, 0);
        part = Math.max(0, Math.min(pag.parts(verseAt(order, seq)) - 1, part));
        prefs.edit().putInt(posKey, seq).putInt(partKey, part).apply();

        // The shared background carries on behind the feed; ease it into this verse's colours.
        background.startSegment();
        background.animateTo(gradient(verseAt(order, seq)));

        VersePager pager = new VersePager(this);
        pager.setAdapter(new VersePager.Adapter() {
            @Override
            public View create(ViewGroup parent) {
                return createPage();
            }

            @Override
            public void bind(View page, long key) {
                bindPage(page, verseAt(order, seqOf(key)), partOf(key), mode);
            }

            @Override
            public long next(long key) {
                int s = seqOf(key), p = partOf(key);
                if (p + 1 < pag.parts(verseAt(order, s))) return key + 1;
                return s + 1 < n ? key(s + 1, 0) : VersePager.NONE;
            }

            @Override
            public long prev(long key) {
                int s = seqOf(key), p = partOf(key);
                if (p > 0) return key - 1;
                return s > 0 ? key(s - 1, pag.parts(verseAt(order, s - 1)) - 1) : VersePager.NONE;
            }

            @Override
            public void onOffset(View page, float offset) {
                PageHolder h = (PageHolder) page.getTag();
                if (h.intro != null) {
                    // Let the opening animation run unless the user starts swiping.
                    if (Math.abs(offset) < 0.001f) return;
                    h.intro.cancel();
                    h.intro = null;
                }
                applyReveal(h, 1f - Math.min(1f, Math.abs(offset)));
            }
        }, key(seq, part));
        pager.setListener(new VersePager.Listener() {
            @Override
            public void onPageChanged(long k) {
                prefs.edit().putInt(posKey, seqOf(k)).putInt(partKey, partOf(k)).apply();
                setCounter(verseAt(order, seqOf(k)), true);
            }

            @Override
            public void onScroll(long from, long to, float t, float travel) {
                int[] a = gradient(verseAt(order, seqOf(from)));
                int[] b = to == VersePager.NONE ? a : gradient(verseAt(order, seqOf(to)));
                background.update(a, b, t, travel);
            }
        });
        root.addView(pager, match());
        playIntro(pager.currentPage());

        // "verse x/N" in a glass pill, fixed at the top while the verses scroll past; it ticks over
        // when the verse changes. Tagged so it also stays put when you switch Read/Shuffle/Home.
        FrameLayout top = new FrameLayout(this);
        top.setTag(BAR_TAG);
        top.setPadding(0, dp(10), 0, 0);
        insets(top, true, false);
        counter = counterText();
        GradientDrawable glass = new GradientDrawable();
        glass.setColor(fPill);
        glass.setCornerRadius(dp(18));
        glass.setStroke(Math.max(1, dp(1) / 2 + 1), fLightBars ? 0x14000000 : 0x1FFFFFFF);
        counter.setBackground(glass);
        counter.setTextColor(fText);
        counter.setPadding(dp(16), dp(8), dp(16), dp(8));
        top.addView(counter, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
        root.addView(top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        setCounter(verseAt(order, seq), false);
        runTransition();
    }

    private TextView counterText() {
        TextView t = text("", 14, fSub);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.04f);
        t.setGravity(Gravity.CENTER);
        t.setFontFeatureSettings("tnum"); // equal-width digits, so numbers don't shift sideways
        return t;
    }

    /**
     * Shows "verse x/N" for verse index {@code idx}. The text simply changes in place (no fade or
     * motion, so it never flickers while scrolling), and the pill is sized for the widest number
     * in the chapter so it doesn't change width as you go from verse 9 to 10.
     */
    private void setCounter(int idx, boolean animate) {
        if (counter == null) return;
        int last = bible.lastVerseNumber(bible.book[idx], bible.chapter[idx]);
        String s = "verse " + bible.verse[idx] + "/" + last;
        if (s.contentEquals(counter.getText())) return; // same verse (e.g. its second half)
        String widest = "verse " + String.valueOf(last).replaceAll("\\d", "8") + "/" + last;
        int min = (int) Math.ceil(counter.getPaint().measureText(widest))
                + counter.getPaddingLeft() + counter.getPaddingRight();
        if (counter.getMinWidth() != min) counter.setMinWidth(min);
        counter.setText(s);
    }

    // ------------------------------------------------------------------ bottom bar

    private static final int TAB_HOME = 0;
    private static final int TAB_READ = 1;
    private static final int TAB_SHUFFLE = 2;
    private static final int TAB_GOTO = 3;
    private static final String[] TAB_LABELS = {"Home", "Read", "Shuffle", "Go to"};
    private static final int[] TAB_ICONS = {R.drawable.ic_home, R.drawable.ic_read, R.drawable.ic_shuffle,
            R.drawable.ic_search};

    private int navBarHeight() {
        return dp(73);
    }

    /**
     * The universal bottom bar (Home, Read, Shuffle, Go to). It lives outside the screen content,
     * so it stays put while screens slide underneath it.
     */
    private void buildNavBar(FrameLayout frame) {
        FrameLayout bar = new FrameLayout(this);
        LinearLayout row = horizontal();
        for (int i = 0; i < TAB_LABELS.length; i++) {
            final int tab = i;
            LinearLayout cell = vertical();
            cell.setGravity(Gravity.CENTER);
            cell.setBackground(ripple(Color.TRANSPARENT, dp(20), false));
            // Icons only; the name stays as the content description for screen readers.
            cell.setContentDescription(TAB_LABELS[i]);
            ImageView icon = new ImageView(this);
            icon.setImageResource(TAB_ICONS[i]);
            cell.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));
            cell.setOnClickListener(v -> {
                bump(icon);
                onTab(tab);
            });
            row.addView(cell, new LinearLayout.LayoutParams(0, navBarHeight(), 1f));
            navIcons[i] = icon;
        }
        bar.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        navHairline = new View(this);
        bar.addView(navHairline, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(1, dp(1) / 2 + 1), Gravity.TOP));

        // A short glowing bar that glides to the active tab.
        navIndicator = new View(this);
        GradientDrawable ind = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{color(R.color.accent_a), color(R.color.accent_b)});
        ind.setCornerRadius(dp(2));
        navIndicator.setBackground(ind);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dp(32), dp(4), Gravity.TOP | Gravity.START);
        ilp.topMargin = dp(3);
        bar.addView(navIndicator, ilp);

        frame.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        navBar = bar;
        bar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol) placeIndicator(false);
        });
    }

    private int currentTab() {
        switch (screen) {
            case SCREEN_FEED:
                return feedMode == MODE_SHUFFLE ? TAB_SHUFFLE : feedMode == MODE_READ ? TAB_READ : TAB_HOME;
            case SCREEN_BOOKS:
            case SCREEN_CHAPTERS:
            case SCREEN_VERSES:
                return TAB_GOTO;
            default:
                return TAB_HOME;
        }
    }

    private void onTab(int tab) {
        if (bible == null) return;
        if (settingsPanel != null) closeSettings();
        int cur = currentTab();
        if (tab == cur) { // tapping the active tab again
            if (tab == TAB_HOME) {
                if (screen == SCREEN_FEED) leaveDaily(false);
                else if (homeScroll != null) homeScroll.smoothScrollTo(0, 0);
            }
            return;
        }
        navDir = tab > cur ? 1 : -1; // slide the way the tabs are laid out
        navKeepBar = screen == SCREEN_FEED && (tab == TAB_READ || tab == TAB_SHUFFLE);
        switch (tab) {
            case TAB_HOME:
                showHome();
                break;
            case TAB_READ:
                showFeed(MODE_READ, -1);
                break;
            case TAB_SHUFFLE:
                showFeed(MODE_SHUFFLE, -1);
                break;
            default:
                browseReturn = screen == SCREEN_FEED ? SCREEN_FEED : SCREEN_HOME;
                showBooks();
        }
    }

    /** Restyles the bottom bar for the current screen and glides its indicator to the active tab. */
    private void updateNavBar() {
        if (navBar == null) return;
        // Glass over the shared background on every screen, light or dark to match the theme.
        boolean night = isNight();
        int bg = night ? 0xD90E0F12 : 0xE8F4F5F7;
        int line = night ? 0x1FFFFFFF : 0x14000000;
        int on = color(R.color.text);
        int off = color(R.color.subtext);
        int fromBg = navBg;
        navBg = bg;
        if (fromBg == 0 || !transitionsOn()) {
            navBar.setBackgroundColor(bg);
        } else if (fromBg != bg) {
            ValueAnimator va = ValueAnimator.ofObject(new ArgbEvaluator(), fromBg, bg);
            va.setDuration(220);
            va.addUpdateListener(a -> navBar.setBackgroundColor((int) a.getAnimatedValue()));
            va.start();
        }
        navHairline.setBackgroundColor(line);
        int active = currentTab();
        for (int i = 0; i < navIcons.length; i++) {
            boolean a = i == active;
            navIcons[i].setImageTintList(ColorStateList.valueOf(a ? on : off));
        }
        boolean changed = active != navActive;
        navActive = active;
        if (changed && transitionsOn() && navIndicator.getWidth() > 0) {
            ImageView icon = navIcons[active];
            icon.setScaleX(0.8f);
            icon.setScaleY(0.8f);
            icon.animate().scaleX(1f).scaleY(1f).setDuration(260)
                    .setInterpolator(new OvershootInterpolator(2.5f)).start();
        }
        placeIndicator(changed);
    }

    private void placeIndicator(boolean animate) {
        if (navBar == null || navActive < 0) return;
        int w = navBar.getWidth();
        if (w == 0) {
            navBar.post(() -> placeIndicator(false));
            return;
        }
        float cell = w / (float) navIcons.length;
        float x = cell * navActive + (cell - dp(32)) / 2f;
        if (animate && transitionsOn()) {
            navIndicator.animate().translationX(x).setDuration(280)
                    .setInterpolator(new PathInterpolator(0.3f, 0f, 0.1f, 1f)).start();
        } else {
            navIndicator.setTranslationX(x);
        }
    }

    /** Hides the bar while the keyboard is up (it would otherwise ride on top of it). */
    private void showNavBar(boolean show) {
        if (navBar == null || (navBar.getVisibility() == View.VISIBLE) == show) return;
        navBar.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------ daily verse in context

    /** From the home page, slide up into a feed that starts at today's verse. */
    private void openDaily() {
        navVertical = true;
        navDir = 1;
        showFeed(MODE_DAILY, dailyIndex());
    }

    /** Back down to the home page (to its bottom, where the swipe started, or to its top). */
    private void leaveDaily(boolean toBottom) {
        navVertical = true;
        navDir = -1;
        pendingHomeScroll = toBottom ? 1_000_000 : 0;
        showHome();
    }

    /** When the feed opens, the first verse's words appear one after another. */
    private void playIntro(View page) {
        final PageHolder h = (PageHolder) page.getTag();
        if (h.reveal == null || !ValueAnimator.areAnimatorsEnabled()) return;
        applyReveal(h, 0f);
        // 0-165 ms the box lands; 165-470 ms the words pour in; 300-500 ms the buttons rise.
        final ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(INTRO_MS);
        va.setInterpolator(new LinearInterpolator()); // easing is applied per element
        va.addUpdateListener(a -> {
            if (h.intro == va) applyReveal(h, (float) a.getAnimatedValue());
        });
        va.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                if (h.intro == va) h.intro = null;
            }
        });
        h.intro = va;
        va.start();
    }

    /**
     * Shows a page's content at arrival progress {@code r} (0 = hidden, 1 = settled). The same
     * curve runs backwards as a page leaves, so buttons go first, then words, then the box.
     */
    private void applyReveal(PageHolder h, float r) {
        r = clamp01(r);
        if (r == h.shown) return;
        h.shown = r;
        float box = easeOut(clamp01(r / BOX_END));
        h.box.setAlpha(box);
        float s = 0.92f + 0.08f * box;
        h.box.setScaleX(s);
        h.box.setScaleY(s);
        h.box.setTranslationY((1f - box) * dp(24));
        if (h.reveal != null && h.reveal.set((r - WORDS_START) / (WORDS_END - WORDS_START))) {
            h.verse.invalidate();
        }
        float btn = easeOut(clamp01((r - BUTTONS_START) / (BUTTONS_END - BUTTONS_START)));
        h.actions.setAlpha(btn);
        h.actions.setTranslationY((1f - btn) * dp(10));
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static float easeOut(float t) {
        float u = 1f - t;
        return 1f - u * u * u;
    }

    /** Colours for the feed's text, verse box, and buttons, to suit the chosen background. */
    private void applyFeedStyle(int bgStyle) {
        if (bgStyle == BG_BLUR_LIGHT) {
            fText = 0xFF1D2025;
            fSub = 0x991D2025;
            fBoxFill = 0xA6FFFFFF;
            fBoxStroke = 0x1F000000;
            fPill = 0x12000000;
            fLightBars = true;
        } else if (bgStyle == BG_BLUR_DARK) {
            fText = Color.WHITE;
            fSub = 0x99FFFFFF;
            fBoxFill = 0x14FFFFFF;
            fBoxStroke = 0x26FFFFFF;
            fPill = 0x1FFFFFFF;
            fLightBars = false;
        } else {
            fText = Color.WHITE;
            fSub = 0xB3FFFFFF;
            fBoxFill = 0x1FFFFFFF;
            fBoxStroke = 0x40FFFFFF;
            fPill = 0x26FFFFFF;
            fLightBars = false;
        }
    }

    /** The verse text view; {@link Paginator} measures with the same settings. */
    private TextView verseText() {
        TextView t = text("", VERSE_SP, fText);
        t.setTypeface(Typeface.SERIF);
        t.setLineSpacing(0, 1.28f);
        t.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);
        t.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        return t;
    }

    private View createPage() {
        FrameLayout page = new FrameLayout(this);
        LinearLayout col = vertical();
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(0, dp(72), 0, dp(20));
        insets(col, true, true);
        col.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));

        PageHolder h = new PageHolder();
        LinearLayout box = vertical();
        GradientDrawable boxBg = new GradientDrawable();
        boxBg.setColor(fBoxFill);
        boxBg.setCornerRadius(dp(26));
        boxBg.setStroke(Math.max(1, dp(1)), fBoxStroke);
        box.setBackground(boxBg);
        box.setPadding(dp(BOX_PAD_DP), dp(20), dp(BOX_PAD_DP), dp(22));

        h.ref = text("", 12, fSub);
        h.ref.setTypeface(Typeface.DEFAULT_BOLD);
        h.ref.setLetterSpacing(0.12f);
        h.ref.setAllCaps(true);
        box.addView(h.ref, matchWrap(0, dp(12)));

        h.verse = verseText();
        box.addView(h.verse, matchWrap(0, 0));

        h.dots = text("", 11, fSub);
        h.dots.setLetterSpacing(0.25f);
        box.addView(h.dots, matchWrap(dp(14), 0));

        LinearLayout.LayoutParams blp = matchWrap(0, 0);
        blp.leftMargin = dp(BOX_MARGIN_DP);
        blp.rightMargin = dp(BOX_MARGIN_DP);
        col.addView(box, blp);
        h.box = box;

        LinearLayout actions = horizontal();
        h.actions = actions;
        actions.setGravity(Gravity.CENTER);
        h.context = pill("Read in context", fPill, fText, null);
        actions.addView(h.context);
        View gap = new View(this);
        actions.addView(gap, new LinearLayout.LayoutParams(dp(8), 1));
        h.share = pill("Share", fPill, fText, null);
        actions.addView(h.share);
        col.addView(actions, matchWrap(dp(18), 0));

        col.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));


        page.addView(col, match());
        page.setTag(h);
        return page;
    }

    private void bindPage(View page, int idx, int part, int mode) {
        PageHolder h = (PageHolder) page.getTag();
        int parts = paginator.parts(idx);
        h.ref.setText(bible.ref(idx));
        if (h.intro != null) {
            h.intro.cancel();
            h.intro = null;
        }
        h.reveal = new WordReveal(paginator.chunk(idx, part));
        h.verse.setText(h.reveal.text, TextView.BufferType.SPANNABLE);
        h.shown = -1f; // force the next applyReveal to restyle the new words
        if (parts > 1) {
            StringBuilder dots = new StringBuilder();
            for (int i = 0; i < parts; i++) dots.append(i == part ? '●' : '○');
            h.dots.setText(dots);
            h.dots.setVisibility(View.VISIBLE);
        } else {
            h.dots.setVisibility(View.GONE);
        }
        // Only Shuffle needs "Read in context" (Read already is; today's verse is read in context
        // already and must not move the Continue reading spot).
        if (mode != MODE_SHUFFLE) {
            h.context.setVisibility(View.GONE);
        } else {
            h.context.setVisibility(View.VISIBLE);
            h.context.setOnClickListener(v -> {
                navDir = -1; // Read sits left of Shuffle
                navKeepBar = true;
                showFeed(MODE_READ, idx);
            });
        }
        h.share.setOnClickListener(v -> share(idx));
    }

    private static long key(int seq, int part) {
        return ((long) seq << 8) | part;
    }

    private static int seqOf(long key) {
        return (int) (key >> 8);
    }

    private static int partOf(long key) {
        return (int) (key & 0xFF);
    }

    private static int verseAt(int[] order, int seq) {
        return order == null ? seq : order[seq];
    }

    private static int[] gradient(int verse) {
        return FEED_GRADIENTS[verse % FEED_GRADIENTS.length];
    }

    private void showBooks() {
        screen = SCREEN_BOOKS;
        LinearLayout col = browseScaffold("Books", null);

        // YouVersion-style search: "jn 3", "1 cor 13:4", "ps 23" or any words from a verse.
        final EditText search = new EditText(this);
        search.setHint("Search: John 3:16, Ps 23, or words");
        search.setSingleLine(true);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        search.setTextColor(color(R.color.text));
        search.setHintTextColor(color(R.color.subtext));
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setBackground(ripple(color(R.color.surface), dp(16), true));
        search.setPadding(dp(16), dp(13), dp(16), dp(13));
        col.addView(search, matchWrap(dp(6), dp(6)));

        final LinearLayout results = vertical();
        results.setVisibility(View.GONE);
        col.addView(results, matchWrap(0, 0));

        final LinearLayout browse = vertical();
        sectionLabel(browse, "Old Testament");
        String[] ot = new String[Bible.OLD_TESTAMENT_BOOKS];
        System.arraycopy(Bible.NAMES, 0, ot, 0, ot.length);
        browse.addView(grid(ot, 2, i -> openBook(i)));
        sectionLabel(browse, "New Testament");
        String[] nt = new String[Bible.NAMES.length - Bible.OLD_TESTAMENT_BOOKS];
        System.arraycopy(Bible.NAMES, Bible.OLD_TESTAMENT_BOOKS, nt, 0, nt.length);
        browse.addView(grid(nt, 2, i -> openBook(Bible.OLD_TESTAMENT_BOOKS + i)));
        col.addView(browse, matchWrap(0, 0));

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                runSearch(s.toString(), results, browse);
            }
        });
        search.setOnEditorActionListener((v, actionId, ev) -> {
            for (int i = 0; i < results.getChildCount(); i++) {
                View r = results.getChildAt(i);
                if (r.hasOnClickListeners()) {
                    r.performClick();
                    break;
                }
            }
            return true;
        });
        // Keep the keyboard closed until the search box is tapped.
        col.setFocusableInTouchMode(true);
        col.requestFocus();
    }

    /** Shows reference matches at once, then verse-text matches from a background search. */
    private void runSearch(String raw, LinearLayout results, LinearLayout browse) {
        final int gen = ++searchGen;
        String q = raw.trim();
        results.removeAllViews();
        if (q.isEmpty()) {
            results.setVisibility(View.GONE);
            browse.setVisibility(View.VISIBLE);
            return;
        }
        results.setVisibility(View.VISIBLE);
        browse.setVisibility(View.GONE);

        int shown = 0;
        for (RefSearch.Hit hit : RefSearch.references(bible, q)) {
            final RefSearch.Hit h = hit;
            View row = resultRow(h.title, h.subtitle, h.kind == RefSearch.KIND_BOOK ? 1 : 3, v -> {
                if (h.kind == RefSearch.KIND_BOOK) openBook(h.book);
                else showFeed(MODE_READ, h.index);
            });
            results.addView(row, matchWrap(dp(4), dp(4)));
            popIn(row, Math.min(shown++, 8) * 22L);
        }

        final String needle = RefSearch.normalizeText(q);
        if (RefSearch.letterCount(needle) < 3) {
            if (shown == 0) results.addView(note("No matches"), matchWrap(dp(12), 0));
            return;
        }
        final TextView label = note("Searching verses…");
        results.addView(label, matchWrap(dp(14), dp(4)));
        final Bible b = bible;
        final int refHits = shown;
        handler.postDelayed(() -> {
            if (gen != searchGen) return; // the user kept typing
            new Thread(() -> {
                final int[] found = new int[60];
                final int total = b.search(needle, found);
                runOnUiThread(() -> {
                    if (gen != searchGen || screen != SCREEN_BOOKS) return;
                    if (total == 0) {
                        if (refHits == 0) label.setText("No matches");
                        else results.removeView(label); // the reference results say it all
                        return;
                    }
                    label.setText("VERSES · " + (total > found.length ? found.length + "+" : total)
                            + (total == 1 ? " match" : " matches"));
                    label.setTypeface(Typeface.DEFAULT_BOLD);
                    label.setLetterSpacing(0.08f);
                    int n = Math.min(total, found.length);
                    for (int i = 0; i < n; i++) {
                        final int idx = found[i];
                        View row = resultRow(b.ref(idx), highlight(b.text[idx], needle), 3,
                                v -> showFeed(MODE_READ, idx));
                        results.addView(row, matchWrap(dp(4), dp(4)));
                        popIn(row, Math.min(i, 8) * 22L);
                    }
                });
            }, "verse-search").start();
        }, 200);
    }

    /** Verse text with every occurrence of {@code needle} in bold. */
    private CharSequence highlight(String text, String needle) {
        SpannableString s = new SpannableString(text);
        String norm = RefSearch.normalizeText(text);
        if (norm.length() != text.length()) return s;
        for (int at = norm.indexOf(needle); at >= 0; at = norm.indexOf(needle, at + needle.length())) {
            s.setSpan(new StyleSpan(Typeface.BOLD), at, at + needle.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.setSpan(new ForegroundColorSpan(color(R.color.text)), at, at + needle.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return s;
    }

    private View resultRow(String title, CharSequence subtitle, int maxLines, View.OnClickListener click) {
        LinearLayout row = vertical();
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setBackground(ripple(color(R.color.surface), dp(16), true));
        row.setOnClickListener(click);
        TextView t = text(title, 16, color(R.color.text));
        t.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(t);
        if (subtitle != null && subtitle.length() > 0) {
            TextView s = text("", 14, color(R.color.subtext));
            s.setText(subtitle);
            s.setMaxLines(maxLines);
            s.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(s, matchWrap(dp(2), 0));
        }
        return row;
    }

    private TextView note(String s) {
        TextView t = text(s, 13, color(R.color.subtext));
        t.setPadding(dp(4), 0, dp(4), 0);
        return t;
    }

    private void openBook(int b) {
        browseBook = b;
        if (bible.chapterCount(b) == 1) showVerses(b, 1);
        else showChapters(b);
    }

    private void showChapters(int b) {
        screen = SCREEN_CHAPTERS;
        browseBook = b;
        LinearLayout col = browseScaffold(Bible.NAMES[b], "Choose a chapter");
        String[] labels = new String[bible.chapterCount(b)];
        for (int i = 0; i < labels.length; i++) labels[i] = String.valueOf(i + 1);
        col.addView(grid(labels, 5, i -> showVerses(b, i + 1)));
    }

    private void showVerses(int b, int ch) {
        screen = SCREEN_VERSES;
        browseBook = b;
        prefs.edit().putInt("browse_verses_book", b).putInt("browse_verses_chapter", ch).apply();
        String name = b == 18 ? "Psalm" : Bible.NAMES[b];
        String title = bible.chapterCount(b) == 1 ? Bible.NAMES[b] : name + " " + ch;
        LinearLayout col = browseScaffold(title, "Choose a verse to start scrolling from");
        final int start = bible.chapterStart[b][ch - 1];
        final int end = bible.chapterEnd(b, ch);
        String[] labels = new String[end - start];
        for (int i = 0; i < labels.length; i++) labels[i] = String.valueOf(bible.verse[start + i]);
        col.addView(grid(labels, 5, i -> showFeed(MODE_READ, start + i)));
    }

    /** Common layout for the browse screens; returns the column to fill. */
    private LinearLayout browseScaffold(String title, String subtitle) {
        clearScreen();
        setLightBars(!isNight());
        cascadeIndex = 0;
        ScrollView scroll = new ScrollView(this);
        LinearLayout col = vertical();
        col.setPadding(dp(16), dp(8), dp(16), dp(24));
        scroll.addView(col);
        insets(col, true, true);

        LinearLayout header = horizontal();
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text("‹", 30, color(R.color.text));
        back.setGravity(Gravity.CENTER);
        back.setBackground(ripple(Color.TRANSPARENT, dp(24), false));
        back.setOnClickListener(v -> handleBack());
        back.setContentDescription("Back");
        header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView t = text(title, 24, color(R.color.text));
        t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        header.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(header, matchWrap(0, subtitle == null ? dp(4) : 0));
        if (subtitle != null) {
            TextView s = text(subtitle, 14, color(R.color.subtext));
            s.setPadding(dp(48), 0, 0, 0);
            col.addView(s, matchWrap(0, dp(12)));
        }
        root.addView(scroll, match());
        addStatusScrim();
        runTransition();
        return col;
    }

    private void sectionLabel(LinearLayout col, String s) {
        TextView t = text(s.toUpperCase(Locale.getDefault()), 12, color(R.color.subtext));
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.1f);
        t.setPadding(dp(4), 0, dp(4), 0);
        col.addView(t, matchWrap(dp(16), dp(8)));
    }

    /** A grid of tiles that pop in one after another, row by row, shortly after the screen slides in. */
    private LinearLayout grid(String[] labels, int cols, IntConsumer onClick) {
        LinearLayout g = vertical();
        for (int r = 0; r * cols < labels.length; r++) {
            LinearLayout row = horizontal();
            for (int c = 0; c < cols; c++) {
                final int i = r * cols + c;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(52), 1f);
                lp.setMargins(dp(4), dp(4), dp(4), dp(4));
                if (i < labels.length) {
                    TextView cell = text(labels[i], 15, color(R.color.text));
                    cell.setGravity(Gravity.CENTER);
                    cell.setSingleLine(true);
                    cell.setPadding(dp(8), 0, dp(8), 0);
                    cell.setBackground(ripple(color(R.color.surface), dp(14), true));
                    cell.setOnClickListener(v -> onClick.accept(i));
                    row.addView(cell, lp);
                    // ~12 ms apart, capped so even long lists finish within ~0.4 s.
                    popIn(cell, 70 + Math.min(cascadeIndex++, 28) * 12L);
                } else {
                    row.addView(new View(this), lp);
                }
            }
            g.addView(row);
        }
        return g;
    }

    // ------------------------------------------------------------------ helpers

    private int dailyIndex() {
        int[] list = bible.daily;
        long day = LocalDate.now().toEpochDay();
        return list[(int) Math.floorMod(day, (long) list.length)];
    }

    private void ensureShuffle() {
        if (shuffleOrder != null) return;
        long seed = prefs.getLong("shuffle_seed", 0L);
        if (seed == 0L) {
            seed = new Random().nextLong() | 1L;
            prefs.edit().putLong("shuffle_seed", seed).apply();
        }
        int n = bible.count;
        int[] order = new int[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Random r = new Random(seed);
        for (int i = n - 1; i > 0; i--) {
            int j = r.nextInt(i + 1);
            int t = order[i];
            order[i] = order[j];
            order[j] = t;
        }
        shuffleOrder = order;
    }

    private void share(int i) {
        String body = "“" + bible.text[i] + "”\n— " + bible.ref(i) + " (" + bible.abbr + ")";
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, body);
        startActivity(Intent.createChooser(send, null));
    }

    private View action(String title, String subtitle, View.OnClickListener click) {
        LinearLayout row = vertical();
        row.setPadding(dp(18), dp(16), dp(18), dp(16));
        row.setBackground(ripple(color(R.color.surface), dp(18), true));
        row.setOnClickListener(click);
        TextView t = text(title, 17, color(R.color.text));
        t.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(t);
        TextView s = text(subtitle, 14, color(R.color.subtext));
        row.addView(s, matchWrap(dp(2), 0));
        return row;
    }

    /** Loads a translation on a background thread; falls back to the WEB if it can't be read. */
    private Bible loadBible(int translation) {
        try {
            return Bible.get(this, translation);
        } catch (RuntimeException e) {
            if (translation == 0) throw e;
            prefs.edit().putInt("translation", 0).commit();
            return Bible.get(this, 0);
        }
    }

    // ------------------------------------------------------------------ settings

    /**
     * Opens the settings card. It grows out of the gear (top right of the home screen) while the
     * screen behind dims and the gear turns; its sections pop in one after another.
     */
    private void openSettings(boolean animate) {
        if (settingsPanel != null || bible == null) return;
        final boolean anim = animate && transitionsOn();
        ViewGroup decor = (ViewGroup) getWindow().getDecorView();
        final FrameLayout panel = new FrameLayout(this);
        View scrim = new View(this);
        scrim.setBackgroundColor(0x66000000);
        scrim.setOnClickListener(v -> closeSettings());
        panel.addView(scrim, match());

        LinearLayout card = vertical();
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color(R.color.sheet));
        bg.setCornerRadius(dp(24));
        bg.setStroke(Math.max(1, dp(1) / 2 + 1), color(R.color.stroke));
        card.setBackground(bg);
        card.setElevation(dp(18));
        card.setPadding(dp(18), dp(16), dp(18), dp(20));
        card.setClickable(true); // taps on the card itself don't reach the scrim

        TextView title = text("Settings", 20, color(R.color.text));
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        card.addView(title, matchWrap(0, 0));

        int n = Bible.TRANSLATION_ABBR.length;
        int[] tValues = new int[n];
        for (int i = 0; i < n; i++) tValues[i] = i;
        LinearLayout s1 = settingSection(card, "Translation", Bible.TRANSLATION_ABBR, tValues,
                "translation", 0, this::switchTranslation);
        translationStatus = text(bible.title, 13, color(R.color.subtext));
        translationStatus.setPadding(dp(4), 0, dp(4), 0);
        s1.addView(translationStatus, matchWrap(dp(8), 0));
        // One setting themes everything: the background behind every screen, and light or dark UI.
        LinearLayout s2 = settingSection(card, "Theme", new String[]{"Colorful", "Blur light", "Blur dark"},
                new int[]{BG_COLORFUL, BG_BLUR_LIGHT, BG_BLUR_DARK}, "bg_style", BG_COLORFUL,
                v -> switchTheme());

        int sw = root.getWidth() > 0 ? root.getWidth() : getResources().getDisplayMetrics().widthPixels;
        int w = Math.min(dp(360), sw - dp(24));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        lp.rightMargin = dp(12);
        lp.topMargin = insetTop + dp(96);
        float pivotX = w - dp(26);
        if (gearButton != null && gearButton.isAttachedToWindow() && gearButton.getHeight() > 0) {
            int[] at = new int[2];
            gearButton.getLocationInWindow(at);
            lp.topMargin = at[1] + gearButton.getHeight() + dp(4);
            pivotX = at[0] + gearButton.getWidth() / 2f - (sw - lp.rightMargin - w);
        }
        panel.addView(card, lp);

        // Below the theme-switch cover if one is still blurring away, otherwise on top of everything.
        int at = coverView != null && coverView.getParent() == decor
                ? decor.indexOfChild(coverView) : decor.getChildCount();
        decor.addView(panel, at, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        settingsPanel = panel;
        turnGear(true, anim);
        if (!anim) return;

        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(220).start();
        card.setPivotX(pivotX);
        card.setPivotY(0f);
        card.setAlpha(0f);
        card.setScaleX(0.55f);
        card.setScaleY(0.55f);
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320)
                .setInterpolator(new OvershootInterpolator(1.0f)).start();
        popIn(title, 60);
        popIn(s1, 100);
        popIn(s2, 145);
    }

    /** Shrinks the card back into the gear and lifts the dimming. */
    private void closeSettings() {
        final FrameLayout panel = settingsPanel;
        if (panel == null) return;
        settingsPanel = null;
        translationStatus = null;
        turnGear(false, true);
        Runnable remove = () -> {
            ViewGroup parent = (ViewGroup) panel.getParent();
            if (parent != null) parent.removeView(panel);
        };
        if (!transitionsOn()) {
            remove.run();
            return;
        }
        View scrim = panel.getChildAt(0), card = panel.getChildAt(1);
        scrim.setOnClickListener(null);
        scrim.animate().alpha(0f).setDuration(180).start();
        card.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration(180)
                .setInterpolator(new AccelerateInterpolator(1.6f)).withEndAction(remove).start();
    }

    private void turnGear(boolean open, boolean anim) {
        if (gearButton == null) return;
        float to = open ? 90f : 0f;
        if (anim && transitionsOn()) {
            gearButton.animate().rotation(to).setDuration(320)
                    .setInterpolator(new DecelerateInterpolator(1.8f)).start();
        } else {
            gearButton.setRotation(to);
        }
    }

    private LinearLayout settingSection(LinearLayout card, String label, String[] labels, int[] values,
                                        String prefKey, int def, IntConsumer onPick) {
        LinearLayout sec = vertical();
        TextView l = text(label.toUpperCase(Locale.getDefault()), 12, color(R.color.subtext));
        l.setTypeface(Typeface.DEFAULT_BOLD);
        l.setLetterSpacing(0.1f);
        l.setPadding(dp(4), 0, dp(4), 0);
        sec.addView(l, matchWrap(dp(18), dp(8)));
        sec.addView(segmented(labels, values, prefKey, def, onPick), matchWrap(0, 0));
        card.addView(sec, matchWrap(0, 0));
        return sec;
    }

    /**
     * A segmented control: equal-width options on a track, with a gradient highlight that glides to
     * the option you tap while the labels swap colour and the tapped one gives a little bounce.
     */
    private View segmented(String[] labels, int[] values, String prefKey, int def, IntConsumer onPick) {
        FrameLayout seg = new FrameLayout(this);
        GradientDrawable track = new GradientDrawable();
        track.setColor(color(R.color.track));
        track.setCornerRadius(dp(15));
        seg.setBackground(track);
        seg.setPadding(dp(4), dp(4), dp(4), dp(4));

        final View hl = new View(this);
        GradientDrawable hlBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{color(R.color.accent_a), color(R.color.accent_b)});
        hlBg.setCornerRadius(dp(11));
        hl.setBackground(hlBg);
        seg.addView(hl, new FrameLayout.LayoutParams(0, 0)); // sized to an option once laid out

        final LinearLayout row = horizontal();
        seg.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        final TextView[] opts = new TextView[labels.length];
        int current = 0;
        for (int i = 0; i < values.length; i++) if (values[i] == prefs.getInt(prefKey, def)) current = i;
        final int[] sel = {current};
        final int onColor = Color.WHITE, offColor = color(R.color.text);
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView o = text(labels[i], 14, i == current ? onColor : offColor);
            o.setTypeface(Typeface.DEFAULT_BOLD);
            o.setGravity(Gravity.CENTER);
            o.setSingleLine(true);
            o.setPadding(dp(4), dp(10), dp(4), dp(10));
            o.setBackground(ripple(Color.TRANSPARENT, dp(11), false));
            o.setOnClickListener(v -> {
                bump(v);
                if (idx == sel[0]) return;
                TextView from = opts[sel[0]];
                sel[0] = idx;
                prefs.edit().putInt(prefKey, values[idx]).commit();
                glide(hl, from, (TextView) v, onColor, offColor);
                if (onPick != null) onPick.accept(values[idx]);
            });
            row.addView(o, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            opts[i] = o;
        }
        row.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                row.getViewTreeObserver().removeOnPreDrawListener(this);
                TextView o = opts[sel[0]];
                ViewGroup.LayoutParams lp = hl.getLayoutParams();
                lp.width = o.getWidth();
                lp.height = o.getHeight();
                hl.setLayoutParams(lp);
                hl.setTranslationX(o.getLeft());
                return false; // lay out again with the highlight in place before drawing
            }
        });
        return seg;
    }

    /** Moves a segmented control's highlight from one option to another and swaps their colours. */
    private void glide(View hl, TextView from, TextView to, int onColor, int offColor) {
        float x0 = hl.getTranslationX(), x1 = to.getLeft();
        if (!transitionsOn()) {
            hl.setTranslationX(x1);
            from.setTextColor(offColor);
            to.setTextColor(onColor);
            return;
        }
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(260);
        va.setInterpolator(new PathInterpolator(0.3f, 0f, 0.1f, 1f));
        ArgbEvaluator argb = new ArgbEvaluator();
        va.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            hl.setTranslationX(x0 + (x1 - x0) * t);
            // a slight stretch mid-flight makes the glide feel springy
            hl.setScaleX(1f + 0.08f * (float) Math.sin(Math.PI * t));
            from.setTextColor((int) argb.evaluate(t, onColor, offColor));
            to.setTextColor((int) argb.evaluate(t, offColor, onColor));
        });
        va.start();
    }

    /** Quick press-down-and-spring-back on a tapped control. */
    private void bump(View v) {
        if (!transitionsOn()) return;
        v.animate().cancel();
        v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(70).setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(220)
                        .setInterpolator(new OvershootInterpolator(3f)).start())
                .start();
    }

    /**
     * Loads the translation just picked in the background, keeping the settings card open, then
     * crossfades the screen underneath to the new text. The reading position is carried over by
     * book, chapter and verse (verse indices differ between translations).
     */
    private void switchTranslation(int target) {
        final int gen = ++translationGen;
        if (translationStatus != null) {
            translationStatus.animate().cancel();
            translationStatus.setText("Loading " + Bible.TRANSLATION_TITLES[target] + "…");
            translationStatus.setAlpha(0.5f);
        }
        new Thread(() -> {
            Bible nb = loadBible(target);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || gen != translationGen) return;
                Bible old = bible;
                SharedPreferences.Editor e = prefs.edit()
                        .putInt("read_part", 0)
                        .putInt("shuffle_part", 0)
                        .putInt("shuffle_pos", Math.min(prefs.getInt("shuffle_pos", 0), nb.count - 1));
                int readPos = prefs.getInt("read_pos", -1);
                if (old != null && readPos >= 0 && readPos < old.count) {
                    e.putInt("read_pos", nb.indexAtOrAfter(old.book[readPos], old.chapter[readPos],
                            old.verse[readPos]));
                }
                e.commit();
                bible = nb;
                paginator = null;
                shuffleOrder = null;
                if (translationStatus != null) {
                    translationStatus.setText(nb.title + "  ✓");
                    translationStatus.animate().alpha(1f).setDuration(200).start();
                }
                navDir = 0; // crossfade
                refreshScreen();
            });
        }, "bible-loader").start();
    }

    /** Rebuilds whatever screen is showing (after the translation changes). */
    private void refreshScreen() {
        switch (screen) {
            case SCREEN_FEED:
                showFeed(feedMode, -1);
                break;
            case SCREEN_BOOKS:
                showBooks();
                break;
            case SCREEN_CHAPTERS:
                if (browseBook >= 0) showChapters(browseBook);
                else showBooks();
                break;
            case SCREEN_VERSES:
                showBooks();
                break;
            default:
                int y = homeScroll != null ? homeScroll.getScrollY() : 0;
                pendingHomeScroll = y;
                showHome();
        }
    }

    /**
     * Applies the theme (background + light/dark UI) in place: no activity restart, so it starts
     * on the frame you tap.
     * A snapshot of the old look covers the screen and blurs and fades away while the rebuilt
     * screen underneath comes into focus.
     */
    private void switchTheme() {
        Bitmap shot = null;
        View decor = getWindow().getDecorView(); // includes the settings card, if open
        if (transitionsOn() && decor.getWidth() > 0 && decor.getHeight() > 0) {
            try {
                shot = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
                decor.draw(new Canvas(shot));
            } catch (OutOfMemoryError e) {
                shot = null;
            }
        }
        if (shot != null) coverWith(shot);

        loadThemeResources();
        FeedBackground fresh = makeBackground(); // the shared backdrop in the new style
        appFrame.removeView(background);
        appFrame.addView(fresh, 0, match());
        background = fresh;
        boolean settingsOpen = settingsPanel != null;
        if (settingsOpen) { // rebuild the card in the new colours, already open
            ViewGroup parent = (ViewGroup) settingsPanel.getParent();
            if (parent != null) parent.removeView(settingsPanel);
            settingsPanel = null;
            translationStatus = null;
        }
        navDir = NAV_NONE;
        refreshScreen();
        if (settingsOpen) {
            // Open once the new screen is laid out, so the card sits right under the gear.
            root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    root.getViewTreeObserver().removeOnPreDrawListener(this);
                    openSettings(false);
                    return true;
                }
            });
        }
        if (coverView != null) blurAway(coverView);
    }

    /** Resources for the chosen theme: light UI for "Blur light", dark UI otherwise. */
    private void loadThemeResources() {
        int night = nightFor(prefs.getInt("bg_style", BG_COLORFUL));
        Configuration c = new Configuration(getResources().getConfiguration());
        c.uiMode = (c.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
        themed = createConfigurationContext(c).getResources();
    }

    /** Puts a picture of the current screen on top of everything (it also blocks touches). */
    private void coverWith(Bitmap shot) {
        removeCover();
        ImageView cover = new ImageView(this);
        cover.setImageBitmap(shot);
        cover.setScaleType(ImageView.ScaleType.FIT_XY);
        cover.setClickable(true);
        ((ViewGroup) getWindow().getDecorView()).addView(cover,
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
        coverView = cover;
        coverShot = shot;
    }

    private void removeCover() {
        if (coverView != null) {
            coverView.animate().cancel();
            ViewGroup parent = (ViewGroup) coverView.getParent();
            if (parent != null) parent.removeView(coverView);
        }
        if (root != null && Build.VERSION.SDK_INT >= 31) root.setRenderEffect(null);
        if (coverShot != null) coverShot.recycle();
        coverView = null;
        coverShot = null;
    }

    /**
     * The blur transition: the old look blurs out and fades while the new screen sharpens into
     * focus (Android 12+). Older Android versions get a fade with a slight zoom instead.
     */
    private void blurAway(final ImageView cover) {
        final boolean blur = Build.VERSION.SDK_INT >= 31;
        final float maxBlur = dp(28);
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(420);
        va.setInterpolator(new DecelerateInterpolator(1.4f));
        va.addUpdateListener(a -> {
            if (coverView != cover) return;
            float t = (float) a.getAnimatedValue();
            cover.setAlpha(1f - t);
            if (blur) {
                float out = 0.5f + maxBlur * Math.min(1f, t * 1.6f); // old look: sharp -> very blurry
                cover.setRenderEffect(RenderEffect.createBlurEffect(out, out, Shader.TileMode.CLAMP));
                float in = maxBlur * 0.6f * (1f - t); // new look: slightly blurry -> sharp
                root.setRenderEffect(in < 0.5f ? null
                        : RenderEffect.createBlurEffect(in, in, Shader.TileMode.CLAMP));
            } else {
                float s = 1f + 0.04f * t;
                cover.setScaleX(s);
                cover.setScaleY(s);
            }
        });
        va.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                if (coverView == cover) removeCover();
            }
        });
        va.start();
    }

    private TextView pill(String label, int fill, View.OnClickListener click) {
        return pill(label, fill, Color.WHITE, click);
    }

    private TextView pill(String label, int fill, int textColor, View.OnClickListener click) {
        TextView p = text(label, 14, textColor);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setGravity(Gravity.CENTER);
        p.setPadding(dp(16), dp(9), dp(16), dp(9));
        p.setBackground(ripple(fill, dp(20), false));
        if (click != null) p.setOnClickListener(click);
        return p;
    }

    private TextView tab(String label, boolean active, View.OnClickListener click) {
        TextView t = text(label, 16, active ? fText : fSub);
        t.setTypeface(active ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        t.setPadding(dp(12), dp(8), dp(12), dp(8));
        if (!fLightBars) t.setShadowLayer(dp(4), 0, 0, 0x66000000);
        t.setBackground(ripple(Color.TRANSPARENT, dp(18), false));
        t.setOnClickListener(click);
        return t;
    }

    private Drawable ripple(int fill, int radius, boolean stroke) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        bg.setCornerRadius(radius);
        if (stroke) bg.setStroke(Math.max(1, dp(1) / 2 + 1), color(R.color.stroke));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(radius);
        return new RippleDrawable(ColorStateList.valueOf(0x33808080), bg, mask);
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private LinearLayout vertical() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout horizontal() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static LinearLayout.LayoutParams matchWrap(int top, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        lp.bottomMargin = bottom;
        return lp;
    }

    private int color(int id) {
        return themed.getColor(id, null);
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
