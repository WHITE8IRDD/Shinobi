# Shinobi

**A free, open-source Android video player for YouTube.** Search, watch and organize videos without ads or an account. Optional Google sign-in adds your real YouTube feeds and sync; your private recent history and playback controls still work signed out.

Shinobi is built with Kotlin, Jetpack Compose and AndroidX Media3. It is a community, sideload-first app—not an official YouTube or Google product.

## What it does

### Watch, your way
- Search YouTube without signing in; browse real Home and subscriptions feeds with optional sign-in.
- Adaptive playback up to 1080p, separate video/audio stream handling, quality picker, and stream recovery when YouTube expires a link or the network changes.
- Full-screen landscape, picture-in-picture, a live-video mini-player, background audio and lock-screen/media-notification controls.
- Double-tap seek, a scrubber with buffered progress and SponsorBlock segments, queue / Play next, and resume where you left off.
- Playback speed from **0.5× to 3×** in 0.05 steps, with optional remembered speed; an app-wide audio-only mode reduces video data while backgrounded.

### Accessibility and personal library
- Select creator-provided or auto-generated captions when available. WebVTT and YouTube TTML tracks are parsed locally, captions are synchronized to playback, and your language choice stays on-device.
- Signed-out **Recent on this device** history is private, bounded to 100 videos, and clearable in one tap. It is not uploaded or synced.
- Comments, replies, likes, subscriptions, Watch later, playlists, channel pages and account watch-history sync are available when signed in.

### Thoughtful defaults
- SponsorBlock controls by category: skip, ask, mark on the scrubber, or turn off.
- Material You wallpaper colors, a Shinobi Ember palette, other light/dark themes, reduced-motion support and haptic controls.
- No ad SDK, analytics SDK, tracking SDK, paid API keys or proprietary backend. The app talks directly to YouTube's public site endpoints and, when enabled, SponsorBlock.

## Build it for free

Requirements: Android SDK Platform 36 and JDK 17 or newer. All application dependencies are free/open source; there are no keys or paid services to provision.

```sh
./gradlew testDebugUnitTest assembleDebug
# app/build/outputs/apk/debug/app-debug.apk

./gradlew assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

Android Studio can also open this project directly. The checked-in Gradle wrapper pins Gradle; GitHub Actions runs tests and builds both APK variants on every pull request. Release builds are locally signed with the debug key for convenient personal sideloading. **Do not distribute that signing key**—configure your own private release key before publishing an APK under a stable package identity.

## Project map

```text
app/src/main/java/com/shinobi/app/
  YouTube.kt          extraction models, search, stream selection and cache
  PlaybackService.kt  Media3 session, adaptive streams, background playback and recovery
  PlayerState.kt      UI playback state, queue, quality, captions and account actions
  PlayerUi.kt         watch page, details, mini-player and morph
  VideoView.kt        controls, scrubber, captions, fullscreen and quality picker
  Captions.kt         bounded TTML/WebVTT parsing, secure fetch and language preference
  LocalHistory.kt     private signed-out recent history
  Account.kt          optional YouTube account feeds and sync
  SponsorBlock.kt     category preferences and segment lookup
  Screens.kt          Home, Subscriptions, Search and Library
  Theme.kt / UiKit.kt expressive color, layout and reusable components
app/src/test/         parser tests against real response fixtures plus caption tests
```

## Privacy, accounts and service limits

- Search and playback work without sign-in. Recent history and resume positions are stored in app-private preferences; recent history is capped at 100 items and has a clear control.
- Optional account cookies are encrypted with AES-GCM using an Android Keystore key. Sign-in uses Google's own page inside a WebView, but Shinobi is still an unofficial client. Google may restrict accounts used by unofficial clients; **use a secondary account if you choose to sign in**, and leave it disabled if that risk is unacceptable.
- YouTube changes its site and streaming interfaces without notice. Shinobi can break until its free/open-source extractor is updated. Live streams, members-only streams and DRM-protected content are not supported.
- Use the app only in ways permitted by applicable law and YouTube's terms. Shinobi is not affiliated with or endorsed by YouTube or Google.

## License and attribution

Shinobi is distributed under **GNU GPL version 3 (GPL-3.0)**. Portions of the app are a modified derivative of [Skipii](https://github.com/deciocrytek/Skipii), used under its GPL-3.0 license. See [`NOTICE`](NOTICE) for attribution and third-party licenses. The GPL text is in [`LICENSE`](LICENSE).
