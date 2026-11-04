#!/usr/bin/env python3
"""Export the SVG store artwork with the unchanged shared app icon."""
import base64
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parent.parent
for name in ("feature-graphic", "icon"):
    source = root / "docs/store" / f"{name}.svg"
    logo = root / "app/src/main/res/drawable-nodpi/flipperhero_logo.png"
    embedded = "data:image/png;base64," + base64.b64encode(logo.read_bytes()).decode()
    svg = source.read_text().replace("../../app/src/main/res/drawable-nodpi/flipperhero_logo.png", embedded)
    subprocess.run(["rsvg-convert", "-o", str(source.with_suffix(".png"))], input=svg.encode(), check=True)
