"""Render existing Android vectors, never redraw or modify the owner's artwork.
Writes an SVG preview; rasterize with sharp when presenting it to the owner.
"""
import pathlib
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[2]
ANDROID = '{http://schemas.android.com/apk/res/android}'

def mark(name):
    root = ET.parse(ROOT / 'app/src/main/res/drawable' / name).getroot()
    group = root.find('group')
    a = lambda key: group.attrib[ANDROID + key]
    paths = ''.join(f'<path fill="white" d="{p.attrib[ANDROID + "pathData"]}"/>' for p in group.findall('path'))
    return f'<g transform="translate({a("translateX")} {a("translateY")}) scale({a("scaleX")} {a("scaleY")})">{paths}</g>'

launcher = mark('ic_launcher_foreground.xml')
header = mark('ic_shinobi_mark.xml')
svg = f'''<svg xmlns="http://www.w3.org/2000/svg" width="1100" height="640" viewBox="0 0 1100 640">
<rect width="1100" height="640" fill="#f4f3f6"/>
<g font-family="sans-serif" fill="#23232b">
<text x="48" y="54" font-size="28" font-weight="bold">SHINOBI 1.4 — existing icon regression preview</text>
<text x="48" y="88" font-size="16">Rendered from Android XML. v1.2 paths and placement preserved.</text>
<text x="48" y="138" font-size="18">Installation / launcher (round and rounded-square masks)</text>
</g>
<defs><clipPath id="round"><circle cx="54" cy="54" r="36"/></clipPath>
<clipPath id="square"><rect x="18" y="18" width="72" height="72" rx="16"/></clipPath></defs>
<g transform="translate(18 145) scale(2.5)"><g clip-path="url(#round)"><rect width="108" height="108" fill="#111217"/>{launcher}</g></g>
<g transform="translate(308 145) scale(2.5)"><g clip-path="url(#square)"><rect width="108" height="108" fill="#111217"/>{launcher}</g></g>
<g transform="translate(625 145) scale(2.5)"><circle cx="54" cy="54" r="36" fill="#111217"/>{launcher}
<circle cx="54" cy="54" r="32.4" fill="none" stroke="#dfac50" stroke-width="0.3"/>
<path d="M18 54H90 M54 18V90" stroke="#dfac50" stroke-width="0.3" stroke-dasharray="1 1"/></g>
<g font-family="sans-serif" fill="#23232b"><text x="48" y="440" font-size="18">Home header tile — same mark, existing 38 dp container</text></g>
<rect x="48" y="466" width="1004" height="124" rx="24" fill="white"/>
<g transform="translate(76 484) scale(2.3)"><rect width="38" height="38" rx="11" fill="#c3434b"/>
<g transform="translate(5.7 5.7) scale(1.1083333)">{header}</g></g>
<text x="198" y="548" font-family="sans-serif" font-size="40" font-weight="bold" fill="#23232b">Shinobi</text>
<g font-family="sans-serif" fill="#52525a" font-size="14"><text x="504" y="518">Crosshair: canvas center / enclosing-circle center</text>
<text x="504" y="546">The asymmetric silhouette has unequal bounding-box margins.</text>
<text x="48" y="620">Preview only — no master artwork, launcher drawable or bitmap changes.</text></g>
</svg>'''
path = ROOT / 'branding/preview/v1.4-regression.svg'
path.write_text(svg)
print(path)
