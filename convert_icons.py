#!/usr/bin/env python3
"""Convert all black pixels to white in NekoHub icon PNGs, preserving alpha."""

from PIL import Image
import os

icon_paths = [
    "docs/icon.png",
    "app/src/main/res/mipmap-mdpi/ic_launcher.png",
    "app/src/main/res/mipmap-hdpi/ic_launcher.png",
    "app/src/main/res/mipmap-xhdpi/ic_launcher.png",
    "app/src/main/res/mipmap-xxhdpi/ic_launcher.png",
    "app/src/main/res/mipmap-xxxhdpi/ic_launcher.png",
    "app/src/main/res/ic_launcher-web.png",
]

for rel_path in icon_paths:
    path = os.path.join("/data/data/com.termux/files/home/rikkahub", rel_path)
    img = Image.open(path).convert("RGBA")
    data = img.load()
    changed = 0
    for y in range(img.height):
        for x in range(img.width):
            r, g, b, a = data[x, y]
            if (r, g, b) == (0, 0, 0):
                data[x, y] = (255, 255, 255, a)
                changed += 1
    img.save(path)
    print(f"{rel_path}: {img.size[0]}x{img.size[1]}, {changed} pixels converted")

print("\nAll icons converted.")
