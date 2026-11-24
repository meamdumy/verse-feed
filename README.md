# Verse Feed

An offline, verse-by-verse Bible feed for Android with two public-domain translations: the
World English Bible (WEB) and the Berean Standard Bible (BSB).
No accounts, ads, subscriptions, AI, or network access (the app requests no permissions).

## Features
- **Bottom bar** on every screen (TikTok-style), icons only: Home, Read, Shuffle, Go to (the names
  are kept as content descriptions for screen readers). A glowing indicator
  glides to the active tab and its icon pops; screens slide underneath in the direction of the
  tab you picked while the bar stays put (glassy over the feed, solid elsewhere). It hides while
  the keyboard is open.
- **Today's verse in context**: on Home, keep swiping up past the bottom (or tap "Read in
  context" on the verse-of-the-day card) and the page slides up into a feed starting at today's
  verse, so you can read the verses around it. It's part of Home (the Home tab stays active), keeps
  its own place, and never changes "Continue reading". Back, or tapping Home, slides back down.
- **Read**: swipe up/down through the whole Bible one verse at a time; remembers where you stopped.
- **Shuffle**: an endless feed of random verses, with "Read in context" to jump into Read mode.
- **Browse / Go to**: book, then chapter, then verse; scrolling continues from there.
- **Search** (top of the Books screen), YouVersion-style: references such as `jn 3:16`,
  `1 cor 13`, `ps 23`, `jude 5` (book names match by prefix or common abbreviation), or any words,
  which search the verse text and bold the matches. Enter opens the first result.
- **Settings** (gear, top right of the home screen) holds Translation and Theme. The card grows out of the gear as the screen dims; each setting is a segmented control
  whose highlight glides to your choice. Tap outside the card or press Back to close it.
- **Translation**: WEB or BSB. It loads in the background while the settings card stays open,
  then the screen crossfades to the new text. Your reading spot is kept by book, chapter and verse.
- **Verse of the day**: rotates daily through `assets/daily.txt` (edit freely; one `BOOK C:V` per
  line, using the book codes in the text files). Every entry is a complete sentence in both
  translations.
- Verses sit in a box, at most 6 lines per screen; longer verses continue on the next swipe
  (dots show which part you're on). A fixed `verse x/N` counter in a glass pill at the top (it
  doesn't scroll) shows the verse number in its chapter. The number changes in place with no fade
  (so it never flickers), with equal-width digits and a pill sized for the chapter's widest number.
- **Theme**: one background shared by every screen (home, browse, search and the feed), which also
  sets light or dark UI. *Colorful* (dark UI): colour gradients that blend into the next verse's
  colours as you swipe, with Home in today's verse's colours. *Blur light* (light UI) / *Blur
  dark* (dark UI): a grey gradient with soft, heavily blurred bubbles drifting behind everything.
  Cards, tiles and the search box are frosted glass over it.
- Android 8.0+ (API 26).

## Motion
- Feed pages shrink and dim as they leave. As a page arrives (tied to your finger) the box rises,
  grows and fades in first; the words fade in one by one only once it has landed; the buttons
  come last. A leaving page plays this in reverse. Opening the feed runs it over 0.55 s
  (box 0-165 ms, words 165-470 ms, buttons 300-500 ms). Page settles take 90-200 ms.
- Screen changes are a "push" (300 ms): the new screen slides fully in while the old one slides
  fully out the other side, in step, so the see-through screens never overlap. Forward and tabs
  to the right come from the right; back and tabs to the left from the left.
  Book, chapter and verse tiles pop in one after another; search results pop in as they appear.
- Read <-> Shuffle: the feed swipes sideways (Shuffle is to the right) while the bottom bar and the
  verse counter stay put. Home <-> today's verse slides up and down.
- Settings card: grows from the gear with a slight overshoot while the gear turns 90° and the
  screen dims (320 ms); its sections pop in one after another; it shrinks back into the gear on
  close (180 ms). Segmented highlights glide with a small stretch (260 ms), labels crossfade their
  colours, and tapped options give a quick press-and-spring bounce.
- Theme: applied in place (no activity restart), so it starts on the frame you tap. The old
  look blurs out and fades while the new one sharpens into focus (420 ms; Android 12+, older
  versions get a fade with a slight zoom), with the settings card still open.
- A light haptic tick per page, and slowly drifting background light.
- Animations stop with the system "Remove animations" setting; haptics follow the system
  touch-feedback setting.

## Install
Download the APK from the [Releases page](../../releases/latest), open it on your phone, and allow
"install unknown apps" for your browser or file manager when prompted. If you build it yourself,
the APK lands in `dist/VerseFeed-<version>.apk`. Or, with USB debugging on:

    adb install -r dist/VerseFeed-2.6.apk

## Build
Needs the Android SDK (build-tools + a platform) and a JDK 17+ (`JAVA_HOME`). No Gradle.

    powershell -ExecutionPolicy Bypass -File build.ps1 -VersionName 2.7 -VersionCode 18

The first build creates `signing/release.jks` (password in `signing/keystore-password.txt`).
Keep that folder, and keep it private (it is git-ignored): updates must be signed with the same
key, or Android will refuse to install them over the existing app. A fresh clone has no key, so
its first build makes a new one; its APKs can't update an install signed with a different key.
Raise `-VersionCode` for each update.

## Layout
- `src/com/versefeed/Bible.java`: loads a translation and handles lookups and verse-text search.
- `src/com/versefeed/RefSearch.java`: turns typed references ("jn 3:16") into results.
- `src/com/versefeed/VersePager.java`: the full-screen vertical swipe pager.
- `src/com/versefeed/PullScroll.java`: the home page's swipe-past-the-bottom gesture.
- `src/com/versefeed/Paginator.java`: splits long verses into 6-line pages.
- `src/com/versefeed/WordReveal.java`: the word-by-word fade-in.
- `src/com/versefeed/FeedBackground.java`: the blending gradient and blurred-bubble backdrops.
- `src/com/versefeed/MainActivity.java`: home, feed, browse and search screens, transitions.
- `assets/web.txt`: WEB text from eBible.org (`engwebp_vpl`), unmodified; see `assets/web_about.htm`.
- `assets/bsb.txt`: BSB text from eBible.org (`engbsb_vpl`); see `assets/bsb_about.htm`. Two
  conversion artifacts were tidied: 29 stray spaces left before punctuation where footnote
  markers had been removed, and a missing space in `EPH 3:14`.

## License
The app's code is under the [MIT License](LICENSE). The Home, Read, Search and Settings icons are
Material Icons by Google (Apache License 2.0).

## Text
Both translations are in the public domain. "World English Bible" is a trademark of eBible.org;
the WEB text is included unchanged, as their terms ask. The Berean Standard Bible is published
by BSB Publishing, LLC and was dedicated to the public domain in 2023.
