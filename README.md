# Verse Feed

An offline, verse-by-verse Bible tiktok-esqu feed for Android
No accounts, ads, subscriptions, AI, or network access (the app requests no permissions). 
This is the alternative to BibleScroll that helps with the scrolling addiction. Frickin' BibleScroll.

<img width="244.8" height="598.4" alt="Screenshot_20261124-003506_Verse Feed" src="https://github.com/user-attachments/assets/fca2d181-df01-40fb-a9aa-a0ecdd7ed838" /> <img width="244.8" height="598.4" alt="Screenshot_20261124-003503_Verse Feed" src="https://github.com/user-attachments/assets/53f01df6-570d-406f-aa7c-058da1ff33ec" /> <img width="244.8" height="598.4" alt="Screenshot_20261124-003459_Verse Feed" src="https://github.com/user-attachments/assets/630833cb-f3ea-460c-886c-7fb4a9c2f5dd" />





## Features
- **Bottom bar**: Just like tiktok
- **Today's verse in context**: See the daily verse(just a random one), scroll to see the context without losing your place
- **Read**: swipe up/down through the whole Bible one verse at a time; remembers where you stopped. 
- **Shuffle**: read verses at random
- **Browse**: you can look stuff up
- **Settings**: Dark/Light Mode, change translation
- **Translation**: WEB or BSB. (NIV ripoffs that are open source)
- Android 8.0+ (API 26).

## Motion
- Like tiktok

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
