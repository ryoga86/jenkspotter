#!/usr/bin/env python3
"""Builds the JenkSpotter icons.

pluginIcon*.svg is derived from the official Jenkins logo (art/jenkins-original.svg, by Charles
Lowell and Frontside, CC BY-SA 3.0, https://jenkins.io/): the background is recoloured (so the
icon is not mistaken for the official Jenkins logo) and the butler peers through a magnifying
glass. The small 16px status icons are simplified drawings of the same head because the original
detail does not survive at that size; the colour of the bow tie carries the status.

Run from the repository root: python3 tools/generate_icons.py
"""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ORIGINAL = ROOT / "art" / "jenkins-original.svg"
OUT = ROOT / "src" / "main" / "resources"

BRAND = "#1f8a8a"

RECOLOR = {
    "path22": ("#d33833", BRAND),  # background circle
    "path24": ("#ef3d3a", "#27a3a3"),  # circle highlight
}


def magnifier(cx: float, cy: float, r: float, hx: float, hy: float) -> str:
    """Lens at (cx, cy) with radius r, handle ending at (hx, hy); in the logo's own coordinate
    system (y axis points up, see the group transform)."""
    return f"""
<g id="magnifier">
  <path d="M {cx + r * 0.62:g},{cy - r * 0.62:g} L {hx:g},{hy:g}" stroke="#1d1919" stroke-width="{r * 0.62:g}" stroke-linecap="round"/>
  <path d="M {cx + r * 0.70:g},{cy - r * 0.70:g} L {hx:g},{hy:g}" stroke="#5a3a22" stroke-width="{r * 0.38:g}" stroke-linecap="round"/>
  <circle cx="{cx:g}" cy="{cy:g}" r="{r:g}" fill="#ffffff" fill-opacity="0.28" stroke="#1d1919" stroke-width="{r * 0.42:g}"/>
  <circle cx="{cx:g}" cy="{cy:g}" r="{r:g}" fill="none" stroke="#c9ced6" stroke-width="{r * 0.2:g}"/>
  <path d="M {cx - r * 0.55:g},{cy + r * 0.15:g} A {r * 0.6:g},{r * 0.6:g} 0 0 1 {cx - r * 0.1:g},{cy + r * 0.58:g}" fill="none"
        stroke="#ffffff" stroke-opacity="0.85" stroke-width="{r * 0.14:g}" stroke-linecap="round"/>
</g>
"""


def plugin_icon() -> str:
    svg = ORIGINAL.read_text()
    svg = re.sub(r"<metadata.*?</metadata>", "", svg, flags=re.S)
    svg = re.sub(r"<sodipodi:namedview.*?/>", "", svg, flags=re.S)
    svg = re.sub(r'\s(?:inkscape|sodipodi):[a-z-]+="[^"]*"', "", svg)
    for pid, (old, new) in RECOLOR.items():
        svg = re.sub(rf'(style="[^"]*){old}([^"]*"\s*id="{pid}")', rf"\g<1>{new}\g<2>", svg, flags=re.I)
    # Lens in front of the butler's left eye (right in the picture), handle down to the right.
    svg = svg.replace("</g></g></svg>", magnifier(141, 192, 23, 182, 146) + "</g></g></svg>")
    svg = re.sub(r'width="226"\s*height="312"', 'width="40" height="40" viewBox="9 -26 213 213"', svg)
    return svg


def head(tie: str, dark: bool, faded: bool = False) -> str:
    """16x16 simplified butler head; the bow tie colour carries the status."""
    skin = "#e0c3a0" if dark else "#f0d6b7"
    outline = "#bdbdbd" if dark else "#231f20"
    hair = "#8c8c8c" if dark else "#6d6b6d"
    return f"""<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 16 16"{' opacity="0.45"' if faded else ''}>
  <path d="M8 1.1c2.7 0 4.5 2 4.5 4.8 0 2.9-2 4.9-4.5 4.9S3.5 8.8 3.5 5.9C3.5 3.1 5.3 1.1 8 1.1z" fill="{skin}" stroke="{outline}" stroke-width="0.8"/>
  <path d="M3.6 6.8C3.1 4.6 4 2.6 5.7 1.8 4.9 3.1 4.7 4.8 5 6.4z" fill="{hair}"/>
  <path d="M12.4 6.8c.5-2.2-.4-4.2-2.1-5 .8 1.3 1 3 .7 4.6z" fill="{hair}"/>
  <g fill="none" stroke="{outline}" stroke-width="0.8" stroke-linecap="round">
    <path d="M5.6 5.5q.8-.7 1.5 0"/>
    <path d="M8.9 5.5q.8-.7 1.5 0"/>
    <path d="M6.2 7.8q1.8 1.2 3.6 0"/>
  </g>
  <path d="M8 13.3 3.2 10.9v4.8zM8 13.3l4.8-2.4v4.8z" fill="{tie}"/>
  <circle cx="8" cy="13.3" r="1.3" fill="{tie}" stroke="{outline}" stroke-width="0.5"/>
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
        "toolWindow": BRAND,
    }
    for name, colour in states.items():
        faded = name == "statusOffline"
        write(f"icons/{name}.svg", head(colour, dark=False, faded=faded))
        write(f"icons/{name}_dark.svg", head(colour, dark=True, faded=faded))


if __name__ == "__main__":
    main()
