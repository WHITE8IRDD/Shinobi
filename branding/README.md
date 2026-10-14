# Shinobi branding

Every place the Shinobi mark appears — the launcher icon, the splash screen, the media
notification, the header tile, About and the sign-in sheet — comes from **one** file:

```
branding/source/shinobi-mark.svg
```

Edit that file, then regenerate the app assets:

```sh
cd branding
npm install          # sharp, for the raster previews only
node generate-icons.mjs
```

The Android drawables are plain vector XML and are written with no dependencies at all, so a
checkout without `node_modules` still regenerates every asset the app ships. `sharp` is only
needed for the PNG previews and the store icon.

## What gets written

| File | Where it appears |
| --- | --- |
| `app/src/main/res/drawable/ic_launcher_foreground.xml` | Adaptive launcher icon, foreground layer |
| `app/src/main/res/drawable/ic_launcher_background.xml` | Adaptive launcher icon, background layer |
| `app/src/main/res/drawable/ic_launcher_monochrome.xml` | Themed icon (Android 13+) |
| `app/src/main/res/drawable/ic_shinobi_mark.xml` | Header brand tile, About, sign-in |
| `app/src/main/res/drawable/ic_notification.xml` | Media notification and lock screen |
| `app/src/main/res/drawable/ic_splash_mark.xml` | Splash screen |
| `app/src/main/res/mipmap-anydpi-v26/ic_launcher{,_round}.xml` | Adaptive-icon descriptors |
| `app/src/main/res/mipmap-*/ic_launcher{,_round}.png` | Legacy launcher bitmaps (48–192 px) |
| `branding/preview/*.png` | Mask, size, splash and in-app previews |
| `branding/store/play-store-icon-512.png` | 512 px listing icon |

## How the sizing is decided

The constraints are Android's, and the generator enforces them rather than leaving them to
taste:

- **Adaptive icon.** The canvas is 108 dp and the launcher masks it to 72 dp. Only the centre
  66 dp is guaranteed to survive *every* mask shape, so the emblem's enclosing circle is fitted
  to 90% of the 36 dp mask radius. It reads as deliberately full — the way a large, confident
  icon should — while staying 0.6 dp inside the guaranteed-visible circle, so no launcher can
  shave a corner off it. The generator fails the build if that ever stops being true.
- **Splash.** The platform draws a 288 dp canvas and guarantees the inner 192 dp. The emblem is
  set to 156 dp across, filling most of that circle with an even margin.
- **In-app mark.** Filled to the same 90% share of its box, so the header tile, About and the
  sign-in sheet show the emblem at the same weight as the icon the user tapped to get there.
- **Notification.** A flat, single-colour stencil at 90% of the 24 dp box: the platform tints it,
  so it carries no colour of its own.

## Colour

The mark is monochrome by design. It is white as three solid shapes — body plus two
eye islands (owner's artwork verified: 0 enclosed holes, 3 disconnected components) —
so it takes on whatever colour it is placed against:

- launcher icon — white on the near-black tile, as in the master artwork;
- in-app — tinted by the theme, so it works in Shinobi Ember, Material You and every palette in
  Appearance, in light and dark mode;
- splash — near-black on the warm light page, white on the dark page;
- themed icons — recoloured by the launcher from the monochrome layer.

Nothing is hard-coded to a single brand hex except the icon tile itself, which is intentionally
near-black so the app icon stays unmistakable on any wallpaper.

## Owner source files

The owner's four PNGs are committed under `source/` with exact filenames (typos kept):
`1 BLACK LOGO.png` and `LOGO WHITE.png` are full lockups (emblem + SHINOBI wordmark);
`LOGO MARK BALCK.png` and `LOGO AMRK WHITE.png` are the mark alone. All four are a
single-colour glyph on transparent with clean alpha — the black versions are a black
glyph on transparent, not a solid rectangle. `shinobi-mark.svg` is a vector trace of
`LOGO MARK BALCK.png` (alpha threshold 128 + contour trace, 28/20/14 pts,
render-vs-source diff 0.40%). The lockups belong where there is horizontal room
(README header); the square slots (launcher, splash, notification, tile) use the mark.
