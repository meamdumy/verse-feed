# Verse Feed

An offline, verse-by-verse Bible tiktok-esque feed for Android and iPhone
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
- Android 8.0+ (API 26), or iPhone with iOS 17+.

## Motion
- Like tiktok

## Contact me on Discord for any problems or requested changes; my username is *meamdumy1*



## Anything down here is from Claude


## Install
Both files are on the [latest release](../../releases/latest): `VerseFeed.apk` for Android,
`VerseFeed.ipa` for iPhone.

### Android
1. On your phone, download `VerseFeed.apk` from the [latest release](../../releases/latest).
2. Open it, and allow "Install unknown apps" for your browser or file manager when asked.
3. Tap **Install**. Later versions install over it and keep your place and settings.

If you build it yourself, the APK lands in `dist/VerseFeed-<version>.apk`. Or, with USB debugging
on:

    adb install -r dist/VerseFeed-2.6.apk

### iPhone (iOS 17+)
Verse Feed isn't on the App Store, so you install the `.ipa` yourself ("sideloading") with your
own Apple ID. A free Apple ID works. You need a Windows PC or a Mac and a USB cable.

1. Download `VerseFeed.ipa` from the [latest release](../../releases/latest) to your computer.
2. Install [Sideloadly](https://sideloadly.io). On Windows, also install **iTunes** and
   **iCloud** from Apple's website (the Microsoft Store versions don't work with Sideloadly).
3. Plug in your iPhone, unlock it, and tap **Trust** when it asks about this computer.
4. Open Sideloadly. Pick your iPhone, drag `VerseFeed.ipa` into the window, type your Apple ID
   email, and click **Start**. Enter your Apple ID password, and the verification code if asked.
   These go only to Apple, which uses them to sign the app for your phone. Some people use a
   spare Apple ID for this.
5. On the iPhone, turn on Developer Mode: **Settings > Privacy & Security > Developer Mode**,
   then restart and confirm. (The option appears after the first install attempt.)
6. Trust your Apple ID: **Settings > General > VPN & Device Management**, tap your Apple ID,
   then **Trust**.
7. Open Verse Feed from the home screen.

With a free Apple ID the app stops opening after **7 days**. Plug the phone in and run
Sideloadly again with the same `.ipa` to renew it; your place and settings are kept. A free
Apple ID can have up to 3 sideloaded apps at a time. A paid Apple Developer account signs it
for a year. [AltStore](https://altstore.io) works too, and can renew the app over Wi-Fi.

## Build

### Android
Needs the Android SDK (build-tools + a platform) and a JDK 17+ (`JAVA_HOME`). No Gradle.

    powershell -ExecutionPolicy Bypass -File build.ps1 -VersionName 2.7 -VersionCode 18

The first build creates `signing/release.jks` (password in `signing/keystore-password.txt`).
Keep that folder, and keep it private (it is git-ignored): updates must be signed with the same
key, or Android will refuse to install them over the existing app. A fresh clone has no key, so
its first build makes a new one; its APKs can't update an install signed with a different key.
Raise `-VersionCode` for each update.

### iPhone
The iOS app is a native SwiftUI version in `ios/`, with its Xcode project described by
`ios/project.yml` ([XcodeGen](https://github.com/yonaskolb/XcodeGen)). It reads the same
`assets/*.txt` texts as the Android app.

- **No Mac:** every push that touches `ios/` runs the **iOS build** workflow on GitHub's macOS
  runners (`.github/workflows/ios.yml`). The unsigned `.ipa` is in the run's `VerseFeed-ipa`
  artifact. You can also start it by hand from the Actions tab.
- **On a Mac** with Xcode 16+:

      brew install xcodegen
      cd ios && xcodegen generate && open VerseFeed.xcodeproj

  Pick your team under Signing & Capabilities to run it on your own iPhone.

## Layout
- `src/com/versefeed/Bible.java`: loads a translation and handles lookups and verse-text search.
- `src/com/versefeed/RefSearch.java`: turns typed references ("jn 3:16") into results.
- `src/com/versefeed/VersePager.java`: the full-screen vertical swipe pager.
- `src/com/versefeed/PullScroll.java`: the home page's swipe-past-the-bottom gesture.
- `src/com/versefeed/Paginator.java`: splits long verses into 6-line pages.
- `src/com/versefeed/WordReveal.java`: the word-by-word fade-in.
- `src/com/versefeed/FeedBackground.java`: the blending gradient and blurred-bubble backdrops.
- `src/com/versefeed/MainActivity.java`: home, feed, browse and search screens, transitions.
- `ios/VerseFeed/`: the iPhone app (SwiftUI). `Feed.swift` is the swipe pager (a port of
  `VersePager`), `AppState.swift` holds navigation and settings, `Background.swift` the
  backdrops, and `Bible.swift`, `RefSearch.swift` and `Paginator.swift` match their Java
  counterparts.
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
