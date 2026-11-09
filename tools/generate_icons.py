#!/usr/bin/env python3
"""Builds the JenkSpotter icons.

pluginIcon*.svg is derived from the official Jenkins logo (art/jenkins-original.svg, by Charles
Lowell and Frontside, CC BY-SA 3.0, https://jenkins.io/): recoloured to Gryffindor colours, with
black messy hair, round glasses and a lightning scar. The small 16px status icons are simplified
drawings of the same head because the original detail does not survive at that size.

Run from the repository root: python3 tools/generate_icons.py
"""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ORIGINAL = ROOT / "art" / "jenkins-original.svg"
OUT = ROOT / "src" / "main" / "resources"

RECOLOR = {
    "path22": ("#d33833", "#7f0909"),  # background circle -> Gryffindor scarlet
    "path24": ("#ef3d3a", "#ae0001"),  # circle highlight
    "path32": ("#6d6b6d", "#1b1716"),  # grey hair -> black
    "path34": ("#dcd9d8", "#3a3330"),  # hair highlights
    "path36": ("#dcd9d8", "#3a3330"),
    "path96": ("#ef3d3a", "#d3a625"),  # bow tie highlights -> gold
    "path98": ("#ef3d3a", "#d3a625"),
}

# Overlays in the logo's own coordinate system (y axis points up, see the group transform).
HAIR = [
    (60, 200), (55, 222), (48, 240), (63, 241), (60, 256), (77, 250), (80, 266), (95, 256),
    (104, 270), (114, 256), (130, 266), (131, 250), (148, 254), (143, 240), (158, 236),
    (149, 222), (153, 208), (144, 214), (139, 228), (131, 224), (125, 239), (118, 241),
    (108, 242), (100, 233), (92, 239), (84, 229), (74, 233), (68, 220), (63, 210),
]
OVERLAY = f"""
<g id="harry">
  <path d="M {' L '.join(f'{x},{y}' for x, y in HAIR)} Z" fill="#1b1716"/>
  <path d="M 117,237 L 109,227 L 116,225 L 107,213" fill="none" stroke="#b3001b" stroke-width="2.6"
        stroke-linecap="round" stroke-linejoin="round"/>
  <g fill="none" stroke="#1d1919" stroke-width="2.6">
    <circle cx="100" cy="196" r="11.5"/>
    <circle cx="139.5" cy="196" r="10.5"/>
    <path d="M 111.3,198 Q 120,203 129,198"/>
    <path d="M 88.6,197 L 60,193"/>
  </g>
  <g fill="#ffffff" fill-opacity="0.18">
    <circle cx="100" cy="196" r="10.2"/>
    <circle cx="139.5" cy="196" r="9.2"/>
  </g>
</g>
"""


def plugin_icon() -> str:
    svg = ORIGINAL.read_text()
    svg = re.sub(r"<metadata.*?</metadata>", "", svg, flags=re.S)
    svg = re.sub(r"<sodipodi:namedview.*?/>", "", svg, flags=re.S)
    svg = re.sub(r'\s(?:inkscape|sodipodi):[a-z-]+="[^"]*"', "", svg)
    for pid, (old, new) in RECOLOR.items():
        svg = re.sub(rf'(style="[^"]*){old}([^"]*"\s*id="{pid}")', rf"\g<1>{new}\g<2>", svg, flags=re.I)
    svg = svg.replace("</g></g></svg>", OVERLAY + "</g></g></svg>")
    svg = re.sub(r'width="226"\s*height="312"', 'width="40" height="40" viewBox="9 -26 213 213"', svg)
    return svg


def head(scar: str, dark: bool, faded: bool = False) -> str:
    """16x16 simplified head; the scar colour carries the status."""
    skin = "#e0c3a0" if dark else "#f0d6b7"
    outline = "#bdbdbd" if dark else "#231f20"
    return f"""<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 16 16"{' opacity="0.45"' if faded else ''}>
  <path d="M8 2.2c3 0 4.9 2.3 4.9 5.6 0 3.6-2.2 6.2-4.9 6.2S3.1 11.4 3.1 7.8C3.1 4.5 5 2.2 8 2.2z" fill="{skin}" stroke="{outline}" stroke-width="0.8"/>
  <path d="M2.6 7.2 2.4 4.6 3.8 4.4 3.6 2.6 5.4 3 5.8 1.2 7.3 2.4 8.4 0.8 9.3 2.3 11 1.3 11.1 3 13 2.8 12.5 4.5 13.6 5.2 12.9 6.6 11.9 5.4 10.6 6.2 9.4 4.9 8.3 5.4 7.4 4.6 6 5.6 4.9 4.9 3.9 6.3Z" fill="{'#2b2522' if not dark else '#121010'}"/>
  <path d="M9.7 2.6 8.4 4.3 9.4 4.5 8.2 6.4" fill="none" stroke="{scar}" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/>
  <g fill="none" stroke="{outline}" stroke-width="0.9">
    <circle cx="6" cy="8.6" r="1.7"/>
    <circle cx="10.2" cy="8.6" r="1.7"/>
    <path d="M7.7 8.6h0.8"/>
  </g>
</svg>
"""


def write(name: str, content: str) -> None:
    path = OUT / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content)
    print("wrote", path.relative_to(ROOT))


def main() -> None:
    icon = plugin_icon()
    write("META-INF/pluginIcon.svg", icon)
    write("META-INF/pluginIcon_dark.svg", icon)
    states = {
        "statusIdle": "#9a9a9a",
        "statusRunning": "#e8a317",
        "statusFailed": "#e5372b",
        "statusSuccess": "#3fa648",
        "statusOffline": "#9a9a9a",
        "toolWindow": "#b3001b",
    }
    for name, colour in states.items():
        faded = name == "statusOffline"
        write(f"icons/{name}.svg", head(colour, dark=False, faded=faded))
        write(f"icons/{name}_dark.svg", head(colour, dark=True, faded=faded))


if __name__ == "__main__":
    main()
