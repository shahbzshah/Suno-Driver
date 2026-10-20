"""Suno Driver launcher icon generator.

Draws the app's signature VU-meter bars (green -> amber, mid-dance) on the
studio-at-night dark background, at every density Android needs:
  - adaptive icon layers (108dp): ic_launcher_background / _foreground
  - legacy composite icons (48dp): ic_launcher / ic_launcher_round
Supersampled 4x then downscaled for clean rounded corners.
"""
from PIL import Image, ImageDraw

SS = 4  # supersample factor

BG = (21, 19, 17)          # #151311 studio-at-night
BG_CARD = (29, 27, 25)     # #1D1B19 subtle inner panel tone
GREEN = (77, 224, 130)     # #4DE082
AMBER = (245, 158, 11)     # #F59E0B
AMBER_LT = (255, 193, 116) # #FFC174

BAR_COLORS = [GREEN, GREEN, AMBER, AMBER, AMBER_LT]
BAR_HEIGHTS = [0.34, 0.58, 0.92, 0.70, 0.44]  # fraction of max height — mid-dance


def draw_bars(size_px: int, scale: float = 1.0) -> Image.Image:
    """Transparent layer with the 5 meter bars, centred."""
    img = Image.new("RGBA", (size_px * SS, size_px * SS), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    s = size_px * SS
    n = 5
    bar_w = s * 0.088 * scale
    gap = s * 0.052 * scale
    total_w = n * bar_w + (n - 1) * gap
    x0 = (s - total_w) / 2
    baseline = s * 0.70
    max_h = s * 0.46 * scale
    radius = bar_w * 0.42
    for i in range(n):
        h = max_h * BAR_HEIGHTS[i]
        x = x0 + i * (bar_w + gap)
        d.rounded_rectangle([x, baseline - h, x + bar_w, baseline],
                            radius=radius, fill=BAR_COLORS[i] + (255,))
    return img.resize((size_px, size_px), Image.LANCZOS)


def draw_background(size_px: int) -> Image.Image:
    """Dark panel; a slightly lighter inset echoes the app's card style."""
    img = Image.new("RGBA", (size_px * SS, size_px * SS), BG + (255,))
    d = ImageDraw.Draw(img)
    s = size_px * SS
    m = s * 0.055
    d.rounded_rectangle([m, m, s - m, s - m], radius=s * 0.16, fill=BG_CARD + (255,))
    return img.resize((size_px, size_px), Image.LANCZOS)


def composite(size_px: int, round_mask: bool = False) -> Image.Image:
    icon = draw_background(size_px)
    icon.alpha_composite(draw_bars(size_px))
    if round_mask:
        mask = Image.new("L", (size_px * SS, size_px * SS), 0)
        md = ImageDraw.Draw(mask)
        md.ellipse([0, 0, size_px * SS, size_px * SS], fill=255)
        mask = mask.resize((size_px, size_px), Image.LANCZOS)
        icon.putalpha(mask)
    return icon


DENSITIES = {  # name: dpi scale vs mdpi
    "mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0,
}
RES = "/home/hatch/workspace/suno-driver-poc/app/src/main/res"

import os
for name, scale in DENSITIES.items():
    folder = os.path.join(RES, f"mipmap-{name}")
    os.makedirs(folder, exist_ok=True)
    adaptive = int(108 * scale)   # adaptive layers are 108dp
    legacy = int(48 * scale)      # legacy icons are 48dp
    draw_background(adaptive).save(os.path.join(folder, "ic_launcher_background.png"))
    # Foreground bars sit in the adaptive safe zone: scale down a touch.
    draw_bars(adaptive, scale=0.92).save(os.path.join(folder, "ic_launcher_foreground.png"))
    composite(legacy).save(os.path.join(folder, "ic_launcher.png"))
    composite(legacy, round_mask=True).save(os.path.join(folder, "ic_launcher_round.png"))
    print(name, "done")

# Big preview for Megah (rounded-square composite like a launcher shows it).
preview = composite(512)
preview.save("/home/hatch/workspace/your_files/suno-driver-icon-preview.png")
print("preview saved")
