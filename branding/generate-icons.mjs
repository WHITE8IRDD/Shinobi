#!/usr/bin/env node
/**
 * Shinobi icon pipeline.
 *
 * Reads branding/source/shinobi-mark.svg — the master artwork — and writes every place the
 * mark appears in the app:
 *
 *   app/src/main/res/drawable/ic_launcher_foreground.xml   adaptive-icon foreground layer
 *   app/src/main/res/drawable/ic_launcher_background.xml   adaptive-icon background layer
 *   app/src/main/res/drawable/ic_launcher_monochrome.xml   themed-icon (monochrome) layer
 *   app/src/main/res/drawable/ic_shinobi_mark.xml          in-app mark, tinted at the call site
 *   app/src/main/res/drawable/ic_notification.xml          media-notification small icon
 *   app/src/main/res/drawable/ic_splash_mark.xml           splash-screen emblem
 *
 * and a set of human-readable previews plus the store icon under branding/.
 *
 *   cd branding && npm install sharp && node generate-icons.mjs
 *
 * The script is deterministic: the same master SVG always produces byte-identical output, so a
 * diff in a generated file means the artwork actually changed.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..');

// sharp is a build-time-only tool for the raster previews. The Android drawables below are pure
// vector output and need no dependency at all, so a missing sharp still produces every XML asset.
let sharp = null;
try {
  sharp = (await import('sharp')).default;
} catch {
  console.warn('note: sharp is not installed, so only the vector assets will be written.');
}

// ── Master artwork ────────────────────────────────────────────────────────────────────────────

const master = fs.readFileSync(path.join(here, 'source/shinobi-mark.svg'), 'utf8');
const pathById = (id) => {
  const m = master.match(new RegExp(`<path[^>]*id="${id}"[^>]*d="([^"]+)"`));
  if (!m) throw new Error(`master SVG has no path with id="${id}"`);
  return m[1];
};
const body = pathById('body');
const pathByIdOptional = (id) => {
  const m = master.match(new RegExp(`<path[^>]*id="${id}"[^>]*d="([^"]+)"`));
  return m ? m[1] : '';
};
const eyeLeft = pathByIdOptional('eye-left');
const eyeRight = pathByIdOptional('eye-right');
const hasEyes = eyeLeft !== '' && eyeRight !== '';
if (eyeLeft === '' || eyeRight === '') console.log('master has no eye paths: emitting body only');
const eyeSvg = (fill) => hasEyes ?
  `<path fill="${fill}" d="${eyeLeft}"/><path fill="${fill}" d="${eyeRight}"/>` : '';

/** Every control point and anchor in a path, read as (x, y) pairs in order. The master uses
 *  absolute M / L / Q / Z commands with explicit coordinate pairs, so pairing the numbers in
 *  order reconstructs the outline exactly. Control points are included: they can sit a hair
 *  outside the curve, which only ever makes the fit slightly conservative — never a clipped
 *  corner on a launcher mask. */
const anchors = (d) => {
  const nums = (d.match(/-?\d*\.?\d+/g) ?? []).map(Number);
  if (nums.length % 2 !== 0) throw new Error('master path has an odd number of coordinates');
  const out = [];
  for (let i = 0; i < nums.length; i += 2) out.push([nums[i], nums[i + 1]]);
  return out;
};

/** Exact minimum enclosing circle over pairs and triples: deterministic, no sampling. */
function enclosingCircle(points) {
  const eps = 1e-7;
  const covers = (c, r) => points.every((p) => Math.hypot(p[0] - c[0], p[1] - c[1]) <= r + eps);
  let best = null;
  const consider = (c, r) => {
    if (!covers(c, r)) return;
    if (!best || r < best.r - eps) best = { c, r };
  };
  for (let i = 0; i < points.length; i++) {
    for (let j = i + 1; j < points.length; j++) {
      const [a, b] = [points[i], points[j]];
      consider([(a[0] + b[0]) / 2, (a[1] + b[1]) / 2], Math.hypot(a[0] - b[0], a[1] - b[1]) / 2);
    }
  }
  for (let i = 0; i < points.length; i++)
    for (let j = i + 1; j < points.length; j++)
      for (let k = j + 1; k < points.length; k++) {
        const [a, b, c] = [points[i], points[j], points[k]];
        const d = 2 * (a[0] * (b[1] - c[1]) + b[0] * (c[1] - a[1]) + c[0] * (a[1] - b[1]));
        if (Math.abs(d) < 1e-9) continue;
        const sq = (p) => p[0] * p[0] + p[1] * p[1];
        const cx = (sq(a) * (b[1] - c[1]) + sq(b) * (c[1] - a[1]) + sq(c) * (a[1] - b[1])) / d;
        const cy = (sq(a) * (c[0] - b[0]) + sq(b) * (a[0] - c[0]) + sq(c) * (b[0] - a[0])) / d;
        consider([cx, cy], Math.hypot(a[0] - cx, a[1] - cy));
      }
  return best;
}

const circle = enclosingCircle(anchors(body));

/**
 * Where the eye sees the middle of the mark: the centre of its bounding box. The minimum
 * enclosing circle (kept above for the log) is pulled towards the glyph's widest sweep, so
 * centring on it placed the emblem ~2.7 dp right of the launcher tile's centre; the bounding
 * box is what every icon grid and design tool centres on.
 */
const bounds = (() => {
  const pts = anchors(body);
  const xs = pts.map((p) => p[0]);
  const ys = pts.map((p) => p[1]);
  const box = { minX: Math.min(...xs), maxX: Math.max(...xs), minY: Math.min(...ys), maxY: Math.max(...ys) };
  const center = [(box.minX + box.maxX) / 2, (box.minY + box.maxY) / 2];
  // The circle around that centre that contains every point of the mark: the fit radius.
  const radius = Math.max(...pts.map((p) => Math.hypot(p[0] - center[0], p[1] - center[1])));
  return { ...box, center, radius, width: box.maxX - box.minX, height: box.maxY - box.minY };
})();

/**
 * Places the mark inside a square canvas so that its visual centre (bounding-box centre) sits
 * dead centre and every point of it lies within `radius` canvas units of that centre. Stating
 * the fit as a circle around the centre is what keeps the mark clear of round launcher masks.
 */
function placement({ canvas, radius }) {
  const s = radius / bounds.radius;
  return {
    s,
    tx: canvas / 2 - bounds.center[0] * s,
    ty: canvas / 2 - bounds.center[1] * s,
  };
}

const group = ({ s, tx, ty }) =>
  `        android:translateX="${tx.toFixed(3)}"\n` +
  `        android:translateY="${ty.toFixed(3)}"\n` +
  `        android:scaleX="${s.toFixed(6)}"\n` +
  `        android:scaleY="${s.toFixed(6)}"`;

/** Body plus optional solid eye islands (disconnected, no holes). The v1.2 mark
 *  had 2 eye islands; the current official mark is one connected silhouette with
 *  a single eye notch. Eye paths are emitted only when the master defines them. */
const eyeXml = (fill) => hasEyes ?
  `        <path\n            android:fillColor="${fill}"\n            android:pathData="${eyeLeft}" />\n` +
  `        <path\n            android:fillColor="${fill}"\n            android:pathData="${eyeRight}" />\n` : '';

const VECTOR = `<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by branding/generate-icons.mjs from branding/source/shinobi-mark.svg. -->

<!--
  __DOC__
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="__W__dp"
    android:height="__H__dp"
    android:viewportWidth="__W__"
    android:viewportHeight="__H__">
    <group
__GROUP__>
        <path
            android:fillColor="__FILL__"
            android:pathData="${body}" />
__EYES__    </group>
</vector>
`;

function vector({ w, h = w, placement: p, fill, doc, extra = '' }) {
  return VECTOR.replaceAll('__W__', String(w))
    .replaceAll('__H__', String(h))
    .replace('__GROUP__', group(p))
    .replaceAll('__FILL__', fill)
    .replaceAll('__EXTRA__', extra)
    .replace('__EYES__', eyeXml(fill))
    .replace('__DOC__', doc);
}

// Adaptive icons: a 108 dp canvas, masked by the launcher to a 72 dp shape. Only the centre
// 66 dp is guaranteed to survive every mask shape, so that is the budget the mark is fitted to:
// every point of it is held inside a 33 dp radius, which puts the emblem comfortably inside
// a round, squircle, rounded-square or teardrop launcher mask without ever being cropped.
const ADAPTIVE = 108;
const MASK_RADIUS = 36;
const SAFE_RADIUS = 33; // the guaranteed-visible area: a 66 dp circle centred on the canvas
// The emblem's fit radius on the launcher tile: 27 dp, three quarters of the 36 dp mask radius.
// The glyph then stands 49 dp tall and 44 dp wide in the 72 dp visible disc — a medium mark with
// an even 11–14 dp of tile around it on every side (Material's keyline for a product logo is
// 44–52 dp), instead of the earlier 63 dp glyph that ran to within 4 dp of the top and bottom
// of the mask and sat 2.7 dp right of centre.
const MARK_RADIUS = 27;
const adaptive = placement({ canvas: ADAPTIVE, radius: MARK_RADIUS });
// The in-app mark's fit radius in its 24 dp box: the glyph stands 21 dp tall with an even
// 1.5 dp margin, centred on its visual centre like the launcher icon.
const IN_APP_RADIUS = 11.5;

// Splash emblem: radius in the 288 dp splash canvas. The platform reserves the inner 192 dp
// circle (radius 96), so a 78 dp radius leaves 18 dp of breathing room all round.
const SPLASH_RADIUS = 78;

const files = {
  // Foreground layer: white mark, transparent background; the background layer supplies colour.
  'app/src/main/res/drawable/ic_launcher_foreground.xml': vector({
    w: ADAPTIVE,
    placement: adaptive,
    fill: '#FFFFFFFF',
    doc:
      "The Shinobi emblem, foreground layer of the adaptive launcher icon.\n" +
      '  The launcher masks the 108 dp canvas to its own shape, so the mark is fitted to the\n' +
      '  guaranteed-visible centre as one solid silhouette.',
  }),

  // Background layer: full bleed, so any mask shape is filled edge to edge.
  'app/src/main/res/drawable/ic_launcher_background.xml': `<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by branding/generate-icons.mjs from branding/source/shinobi-mark.svg. -->

<!--
  Background layer of the adaptive launcher icon. Full bleed - it has to fill whatever mask
  shape the launcher applies - with a whisper of a top-down lift so the tile has depth instead
  of reading as flat black. Dark by design: the mark is a white silhouette on black, exactly as
  the brand artwork is drawn.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:pathData="M0,0 H108 V108 H0 Z">
        <aapt:attr xmlns:aapt="http://schemas.android.com/aapt" name="android:fillColor">
            <gradient
                android:type="linear"
                android:startX="54" android:startY="0"
                android:endX="54" android:endY="108"
                android:startColor="#FF1A1B20"
                android:endColor="#FF0B0B0E" />
        </aapt:attr>
    </path>
</vector>
`,

  // Monochrome layer for Android 13+ themed icons. Silhouette only: the launcher recolours it.
  'app/src/main/res/drawable/ic_launcher_monochrome.xml': vector({
    w: ADAPTIVE,
    placement: adaptive,
    fill: '#FFFFFFFF',
    doc:
      'Themed-icon layer (Android 13+). Silhouette only, in one colour, so the launcher can\n' +
      "  recolour it to match the user's wallpaper; it must never depend on Shinobi's own palette.",
  }),

  // In-app mark: tinted by the call site, so it works in every palette and in both themes.
  'app/src/main/res/drawable/ic_shinobi_mark.xml': vector({
    w: 24,
    placement: placement({ canvas: 24, radius: IN_APP_RADIUS }),
    fill: '#FFFFFFFF',
    doc:
      'The Shinobi emblem for in-app use - the header brand tile, the account sheet and About.\n' +
      '  Fill is white and the caller tints it, and the two eyes are solid islands,\n' +
      "  the surrounding surface's colour in every palette instead of being pinned to one.",
  }),

  // Media notification: a flat white stencil, as the platform requires.
  'app/src/main/res/drawable/ic_notification.xml': vector({
    w: 24,
    placement: placement({ canvas: 24, radius: 11 }),
    fill: '#FFFFFFFF',
    doc:
      'Small icon for the media notification and the lock screen. Android draws this as a flat\n' +
      '  white stencil, ignoring any colour in it, so it stays a single-colour silhouette.',
  }),

  // Splash: the platform draws this 288 dp canvas and guarantees the artwork inside the inner
  // 192 dp circle - on Android 12+ natively, and below that the compat library masks exactly the
  // same circle. The emblem is set to 156 dp across: most of that circle, with the margin that
  // keeps it clear of the masked edge. Colour comes from the theme, light or dark.
  'app/src/main/res/drawable/ic_splash_mark.xml': vector({
    w: 288,
    placement: placement({ canvas: 288, radius: SPLASH_RADIUS }),
    fill: '@color/splash_mark',
    doc:
      'Splash-screen emblem. Android 12+ shows this inside a 240 dp circle and expects the art\n' +
      '  to sit within the inner 192 dp of the 288 dp canvas; the colour follows the theme, so the\n' +
      '  emblem is near-black on the warm light splash and white on the dark one.',
  }),

  // Adaptive-icon descriptors. Round and standard are identical: the platform supplies the shape.
  'app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml': `<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by branding/generate-icons.mjs from branding/source/shinobi-mark.svg. -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
`,
  'app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml': `<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by branding/generate-icons.mjs from branding/source/shinobi-mark.svg. -->
<!-- Android supplies the mask, so the round descriptor draws the same layers as the standard one. -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
`,
};

for (const [rel, content] of Object.entries(files)) {
  const out = path.join(root, rel);
  fs.mkdirSync(path.dirname(out), { recursive: true });
  const withHeader = content.includes('Generated by') ? content : content.replace('?>\n', '?>\n');
  fs.writeFileSync(out, withHeader.endsWith('\n') ? withHeader : withHeader + '\n');
  console.log('wrote', rel);
}

// ── Raster previews and the store icon ────────────────────────────────────────────────────────

if (sharp) {
  const previewDir = path.join(here, 'preview');
  const storeDir = path.join(here, 'store');
  fs.mkdirSync(previewDir, { recursive: true });
  fs.mkdirSync(storeDir, { recursive: true });

  const markGradient = (y0, y1) =>
    `<linearGradient id="tile" x1="0" y1="${y0}" x2="0" y2="${y1}" gradientUnits="userSpaceOnUse">
       <stop offset="0" stop-color="#1A1B20"/><stop offset="1" stop-color="#0B0B0E"/></linearGradient>`;

  const maskedIcon = (px, mask) => {
    const { s, tx, ty } = adaptive;
    const shapes = {
      circle: '<circle cx="54" cy="54" r="36"/>',
      squircle: '<rect x="18" y="18" width="72" height="72" rx="21"/>',
      rounded: '<rect x="18" y="18" width="72" height="72" rx="9"/>',
      square: '<rect x="18" y="18" width="72" height="72"/>',
      full: '<rect x="0" y="0" width="108" height="108"/>',
    };
    const clip =
      mask === 'full'
        ? ''
        : `<clipPath id="m">${shapes[mask]}</clipPath>`;
    return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="${mask === 'full' ? '0 0 108 108' : '0 0 108 108'}" width="${px}" height="${px}">
      <defs>${markGradient(0, 108)}${clip}</defs>
      <g ${clip ? 'clip-path="url(#m)"' : ''}>
        <rect width="108" height="108" fill="url(#tile)"/>
        <g transform="translate(${tx.toFixed(3)},${ty.toFixed(3)}) scale(${s.toFixed(6)})">
          <path fill="#FFFFFF" d="${body}"/>${eyeSvg('#FFFFFF')}
        </g>
      </g>
    </svg>`;
  };

  const png = (svg, px) => sharp(Buffer.from(svg)).resize(px).png().toBuffer();

  const write = async (dir, name, buf) => {
    await sharp(buf).png({ compressionLevel: 9 }).toFile(path.join(dir, name));
    console.log('wrote', path.relative(root, path.join(dir, name)));
  };

  // 1. The launcher icon as it appears under the common mask shapes.
  const shapes = ['circle', 'squircle', 'rounded', 'square'];
  const big = await Promise.all(shapes.map((sh) => png(maskedIcon(512, sh), 512)));
  const gap = 28;
  const w = shapes.length * 512 + (shapes.length + 1) * gap;
  await write(
    previewDir,
    'launcher-icon.png',
    await sharp({ create: { width: w, height: 512 + gap * 2, channels: 4, background: '#EDEDF0' } })
      .composite(big.map((input, i) => ({ input, left: gap + i * (512 + gap), top: gap })))
      .png()
      .toBuffer(),
  );

  // 2. Legibility at the sizes a launcher actually draws.
  const sizes = [36, 48, 65, 96, 144];
  const smalls = await Promise.all(sizes.map((px) => png(maskedIcon(px, 'circle'), px)));
  let cursor = gap;
  const row = smalls.map((input, i) => {
    const cell = { input, left: cursor, top: Math.round(gap + (144 - sizes[i]) / 2) };
    cursor += sizes[i] + gap;
    return cell;
  });
  await write(
    previewDir,
    'launcher-sizes.png',
    await sharp({ create: { width: cursor + gap, height: 144 + gap * 2, channels: 4, background: '#EDEDF0' } })
      .composite(row)
      .png()
      .toBuffer(),
  );

  // 3. The splash emblem on both themes, and the mark at in-app sizes.
  const splash = (bg, fg) => `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 288 288" width="288" height="288">
      <rect width="288" height="288" fill="${bg}"/>
      <g transform="translate(${placement({ canvas: 288, radius: SPLASH_RADIUS }).tx.toFixed(3)},${placement({ canvas: 288, radius: SPLASH_RADIUS }).ty.toFixed(3)}) scale(${placement({ canvas: 288, radius: SPLASH_RADIUS }).s.toFixed(6)})">
        <path fill="${fg}" d="${body}"/>${eyeSvg(fg)}</g>
    </svg>`;
  const splashPair = await Promise.all([
    png(splash('#FFF8F7', '#171113'), 288),
    png(splash('#171113', '#FFF8F7'), 288),
  ]);
  await write(
    previewDir,
    'splash.png',
    await sharp({ create: { width: 288 * 2 + gap * 3, height: 288 + gap * 2, channels: 4, background: '#EDEDF0' } })
      .composite(splashPair.map((input, i) => ({ input, left: gap + i * (288 + gap), top: gap })))
      .png()
      .toBuffer(),
  );

  const mark = (px, fg) => `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="${px}" height="${px}">
      <g transform="translate(${placement({ canvas: 24, radius: IN_APP_RADIUS }).tx.toFixed(3)},${placement({ canvas: 24, radius: IN_APP_RADIUS }).ty.toFixed(3)}) scale(${placement({ canvas: 24, radius: IN_APP_RADIUS }).s.toFixed(6)})">
        <path fill="${fg}" d="${body}"/>${eyeSvg(fg)}</g></svg>`;
  const marks = await Promise.all([
    png(mark(96, '#C3434B'), 96),
    png(mark(96, '#FFFFFF'), 96),
    png(mark(96, '#833761'), 96),
    png(mark(96, '#171113'), 96),
  ]);
  const swatches = ['#FFF8F7', '#0B0B0E', '#EED6DF', '#FCF4EE'];
  let sx = gap;
  const markRow = marks.map((input, i) => {
    const cell = { input, left: sx, top: gap };
    sx += 96 + gap;
    return cell;
  });
  await write(
    previewDir,
    'in-app-mark.png',
    await sharp({ create: { width: sx + gap, height: 96 + gap * 2, channels: 4, background: '#EDEDF0' } })
      .composite([
        ...swatches.map((bg, i) => ({
          input: Buffer.from(
            `<svg xmlns="http://www.w3.org/2000/svg" width="96" height="96"><rect width="96" height="96" fill="${bg}"/></svg>`,
          ),
          left: gap + i * (96 + gap),
          top: gap,
        })),
        ...markRow,
      ])
      .png()
      .toBuffer(),
  );

  // 4. Store listing icon: the same tile as the launcher draws, full bleed at 512 px.
  await write(storeDir, 'play-store-icon-512.png', await png(maskedIcon(512, 'full'), 512));

  // 5. Legacy launcher PNGs. minSdk is 26, so every device has adaptive icons and the drawables
  //    above are what actually gets drawn; these exist so `@mipmap/ic_launcher` still resolves
  //    correctly in tooling, launchers and stores that read the classic mipmap instead.
  const LEGACY = [
    ['mipmap-mdpi', 48],
    ['mipmap-hdpi', 72],
    ['mipmap-xhdpi', 96],
    ['mipmap-xxhdpi', 144],
    ['mipmap-xxxhdpi', 192],
  ];
  for (const [dir, px] of LEGACY) {
    const out = path.join(root, 'app/src/main/res', dir);
    fs.mkdirSync(out, { recursive: true });
    for (const [name, shape] of [['ic_launcher', 'squircle'], ['ic_launcher_round', 'circle']]) {
      await sharp(await png(maskedIcon(px, shape), px)).png({ compressionLevel: 9 }).toFile(path.join(out, `${name}.png`));
      console.log('wrote', path.relative(root, path.join(out, `${name}.png`)));
    }
  }
}

// ── Guards ────────────────────────────────────────────────────────────────────────────────────
// These are the constraints that make the icon correct on every launcher; fail loudly rather
// than ship an emblem that is cropped, or one that looks undersized next to other apps.
const markRadius = bounds.radius * adaptive.s;
if (markRadius > SAFE_RADIUS + 1e-6) {
  throw new Error(`launcher emblem is ${markRadius.toFixed(2)} dp but only ${SAFE_RADIUS} dp is safe`);
}
if (markRadius < SAFE_RADIUS * 0.70) {
  throw new Error(`launcher emblem is ${markRadius.toFixed(2)} dp: too small to look deliberate`);
}
// The glyph's bounding box must stay inside the visible disc with a real margin on every side.
const glyph = {
  left: bounds.minX * adaptive.s + adaptive.tx,
  right: bounds.maxX * adaptive.s + adaptive.tx,
  top: bounds.minY * adaptive.s + adaptive.ty,
  bottom: bounds.maxY * adaptive.s + adaptive.ty,
};
const margins = [glyph.left - (54 - MASK_RADIUS), (54 + MASK_RADIUS) - glyph.right, glyph.top - (54 - MASK_RADIUS), (54 + MASK_RADIUS) - glyph.bottom];
if (margins.some((m) => m < 8)) throw new Error(`launcher emblem crowds the mask: margins ${margins.map((m) => m.toFixed(1)).join(', ')} dp`);
if (Math.abs((glyph.left + glyph.right) / 2 - 54) > 0.01 || Math.abs((glyph.top + glyph.bottom) / 2 - 54) > 0.01) {
  throw new Error('launcher emblem is not centred on the tile');
}
if (SPLASH_RADIUS > 96) throw new Error('splash emblem would be clipped by the 192 dp visible circle');

// Regression-only: verify the serialized transforms (the actual Android precision), not
// just the ideal floating-point placement. No paths or placement rules are changed.
for (const [canvas, radius] of [[108, MARK_RADIUS], [24, IN_APP_RADIUS], [24, 11], [288, SPLASH_RADIUS]]) {
  const p = placement({ canvas, radius });
  const scale = Number(p.s.toFixed(6));
  const center = [Number(p.tx.toFixed(3)) + bounds.center[0] * scale,
    Number(p.ty.toFixed(3)) + bounds.center[1] * scale];
  if (center.some(value => Math.abs(value - canvas / 2) > 0.001)) {
    throw new Error(`mark center drifted on ${canvas} dp canvas: ${center}`);
  }
}

console.log('\nmark bounding box: centre', bounds.center.map((v) => v.toFixed(1)).join(', '), 'fit radius', bounds.radius.toFixed(2),
  '(enclosing circle: centre', circle.c.map((v) => v.toFixed(1)).join(', '), 'radius', circle.r.toFixed(2) + ')');
console.log('adaptive scale', adaptive.s.toFixed(6), '-> fit radius', markRadius.toFixed(2), 'dp of', MASK_RADIUS, 'dp mask;',
  'glyph', (bounds.width * adaptive.s).toFixed(1), 'x', (bounds.height * adaptive.s).toFixed(1), 'dp; margins', margins.map((m) => m.toFixed(1)).join('/'), 'dp');
