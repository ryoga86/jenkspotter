#!/usr/bin/env python3
"""Builds the JenkSpotter icons.

META-INF/pluginIcon.svg (and the _dark copy) is the round plugin icon art/plugin-marketplace.svg, copied as it is:
its white ring keeps it readable on light and dark backgrounds, so both themes use the same file.
The small 16px status and tool window icons are simplified butler heads drawn here; the colour of the bow tie
carries the status.

Run from the repository root: python3 tools/generate_icons.py
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PLUGIN_ICON = ROOT / "art" / "plugin-marketplace.svg"
OUT = ROOT / "src" / "main" / "resources"

BRAND = "#1f8a8a"


def plugin_icon() -> str:
    return PLUGIN_ICON.read_text()


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
