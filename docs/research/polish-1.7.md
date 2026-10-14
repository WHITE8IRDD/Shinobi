# 1.7 implementation notes and limits

## SponsorBlock
Reviewed the upstream hash-prefix route and parameter parser on 2026-10-14:
- https://github.com/ajayyy/SponsorBlockServer/blob/master/src/routes/getSkipSegmentsByHash.ts
- https://github.com/ajayyy/SponsorBlockServer/blob/master/src/utils/parseSkipSegments.ts

Keep the existing privacy-preserving four-hex SHA-256 prefix lookup, category settings,
coloured marks, auto-skip and Undo. Use `actionTypes=["skip"]` (plural). Reject missing
UUIDs, non-finite/negative/reversed ranges and non-skip actions. Accept sub-second valid
segments. Community coverage is not guaranteed. No authenticated submission API added.

## Thumbnail classifier: experimental, not a safety guarantee
Reviewed Yahoo Open NSFW's contextual/accuracy disclaimer and GantMan NSFW's five-class
model. A `sexy` score or bare skin is not an accurate way to distinguish swimwear, music,
health education or sexual intent. No title keywords, skin-colour rules, music-category
blacklists or generic age restrictions are used as sexual labels. YouTube RSS categories
are not reliable sexual-content ratings; inventing that signal would create false positives.

- https://github.com/yahoo/open_nsfw
- https://github.com/GantMan/nsfw_model
- Android conversion: https://github.com/devzwy/TensorflowLite-NSFW-Android/tree/c3efe0a3a380e81fa944b291aee7a3eff7ab6339

Bundled asset `nsfw.tflite`, upstream git blob `a13a741bd8a041f3eda2265b17a13b79937fa17c`.
SHA-256: `eb3b446a6a8c1a73998a76011b97cfc67bc01084c63ee195c774e71344a66442`.
Apache conversion licence and upstream BSD licence are shipped beside the model.
Input: float32 [1,224,224,3], BGR minus [104,117,123]. Output: [SFW, NSFW].

Opt-in, off by default. A full-thumbnail score AND center-crop score >= 0.995 triggers an
opaque recoverable cover (works on Android 8, unlike RenderEffect blur). Scores are not
calibrated probabilities. There is one inference worker and at most 256 cached results.
No image is uploaded to an external classification service. Checking thumbnails have a
neutral placeholder and Show anyway; network/model failure fails open with a visible warning.
Home video previews are disabled while filtering, so they cannot bypass the thumbnail cover.

**Not completed/certified:** broad suggestive-content detection, zero false positives, or a
representative contextual benchmark. This conservative explicit-thumbnail prototype may
miss suggestive images and may still cover ordinary images. It does not scan video frames,
Shorts playback or audio and is not parental control. Do not claim otherwise in release notes.
A production accuracy promise needs a licensed labelled evaluation set, contextual review,
subgroup/error analysis and agreed precision/recall criteria before wider default-on use.

## Playback and performance
- Media3 1.10.1 playlist preloading: https://github.com/androidx/media/blob/1.10.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/ExoPlayer.java
- One service player; next Short is a playlist item, not an offscreen decoder. Preload target
  3 seconds. Current Short loops; swiping commits only at settledPage. Thumbnails cover
  stale frames until the new item's first rendered frame.
- Stream extraction cache capped at 48 items; at most four queued prefetch IDs, deduplicated.
- Existing orientation policy follows system auto-rotate and preserves manual entry while
  rotation lock is on. Fit preserves aspect; Fill crops without stretching.
- Scrub preview shows time and chapter title; chapters come from the extractor. It does not
  pretend to be a decoded frame/storyboard preview at every arbitrary timestamp.
- Autoplay chooses a related video after the queue ends while the UI controller is alive.
  User queues, background service playback, captions, quality and PiP are retained.
- Reports open YouTube's authenticated UI; they are not submitted silently by Shinobi.

No claim of exact YouTube algorithm parity, zero lag, or physical-device profiling. The
sandbox has no Android SDK/JDK; builds and emulator checks run through GitHub Actions.
