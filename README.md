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

### Skipping ads and sponsor reads
- **Zero ads.** Streams are extracted directly, so YouTube's ads never load and there is nothing to detect, click past or wait out. There are no interstitials, banners, pre-rolls or rewarded placements anywhere in the app, and no ad, analytics or tracking SDK in the build. See *No ads, enforced* below.
- SponsorBlock finds the sponsor reads that are *inside* a video and takes them out of the way as smoothly as possible: the jump happens the moment the segment starts (nothing of it is ever shown), abutting segments are merged into a single jump, and the audio fades back up over 250 ms so the cut lands as a soft dip rather than a click.
- Skips keep up with how you actually watch: a position watch catches segments entered by scrubbing, by resuming inside one, or by segment data that arrives after playback already passed its start — not just a plain play-through.
- Every skip is announced with a "Skipped · Undo" note, and a segment is only ever skipped once per viewing: Undo really undoes, and sitting inside a skipped segment won't throw you out of it again.
- Per-category control (Sponsor, self-promotion, interaction reminder, intro, outro, preview, non-music, filler): skip, ask with a one-tap pill, mark on the scrubber, or off.

### Thoughtful defaults
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

## No ads, enforced

Shinobi's reason to exist is a strict zero-ads policy, so it is not a promise in a README — CI
fails the build if it is ever broken:

- a guard scans the build files, the manifest and the whole repository for advertising and
  tracking SDK references (AdMob, AppLovin, ironSource, MoPub, Chartboost, Vungle, StartApp,
  InMobi, `play-services-ads`, the Facebook SDK, `ads-identifier`, Crashlytics/Firebase
  analytics, Mixpanel, Amplitude, Segment, and a removed predecessor brand name) and stops the
  build if one appears;
- a second guard resolves the release runtime classpath and fails if any advertising or
  tracking SDK is on it;
- the same guard also fails if the name of the project Shinobi was forked from is ever added
  back to the repository.

There is no ad-serving code, no consent flow, no ad identifier and no remote switch that could
turn advertising on, because no such component is linked into the app.

## Privacy, accounts and service limits

- Search and playback work without sign-in. Recent history and resume positions are stored in app-private preferences; recent history is capped at 100 items and has a clear control.
- Optional account cookies are encrypted with AES-GCM using an Android Keystore key. Sign-in uses Google's own page inside a WebView, but Shinobi is still an unofficial client. Google may restrict accounts used by unofficial clients; **use a secondary account if you choose to sign in**, and leave it disabled if that risk is unacceptable.
- YouTube changes its site and streaming interfaces without notice. Shinobi can break until its free/open-source extractor is updated. Live streams, members-only streams and DRM-protected content are not supported.
- Use the app only in ways permitted by applicable law and YouTube's terms. Shinobi is not affiliated with or endorsed by YouTube or Google.

## License and attribution

Shinobi is distributed under **GNU GPL version 3 (GPL-3.0)**. It began as a fork of an earlier free-software YouTube client by Decio Crytek, released under the same license; that license and the freedoms it guarantees are preserved here. See [`NOTICE`](NOTICE) for provenance, attribution and third-party licenses. The GPL text is in [`LICENSE`](LICENSE).
