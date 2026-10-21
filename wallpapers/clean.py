"""Turns Backdrop screensaver captures into clean wallpapers.

- Drops duplicates and frames showing the "Switch Backdrop" menu.
- Removes the bottom-right overlay (clock, weather, credit, hint, button) by mirroring
  the neighbouring ground into it with feathered edges.
- Undoes the screensaver's dimming, then saves 1920x1080 JPEGs.

Usage: python clean.py <captures dir> <output dir>
"""
import glob
import os
import sys

import numpy as np
from PIL import Image, ImageFilter

# Overlay area in 1920x1080 captures (clock + credit + hint + button).
OX0, OY0, OX1, OY1 = 1340, 700, 1860, 1080
# "Switch Backdrop" menu: its white "TV default" button sits here.
MX0, MY0, MX1, MY1 = 1250, 560, 1820, 980


def has_menu(a):
    region = a[MY0:MY1, MX0:MX1].mean(axis=2)
    return (region > 235).sum() > 15000


def clean(img):
    a = np.asarray(img, dtype=np.float32).copy()
    # Replace the overlay box with a mirror of the ground to its left, then feather the
    # top and right edges so no seam shows. Mirroring keeps real photo texture.
    w = OX1 - OX0
    src = a[OY0:OY1, OX0 - w:OX0][:, ::-1]
    alpha = np.ones((OY1 - OY0, w, 1), dtype=np.float32)
    ramp = 44
    alpha[:ramp] *= np.linspace(0, 1, ramp)[:, None, None]
    alpha[:, w - ramp:] *= np.linspace(1, 0, ramp)[None, :, None]
    region = a[OY0:OY1, OX0:OX1]
    a[OY0:OY1, OX0:OX1] = region * (1 - alpha) + src * alpha

    # Backdrop dims photos; lift them back without clipping highlights.
    hi = np.percentile(a, 99.6)
    gain = min(1.55, 248.0 / max(hi, 1))
    a = 255.0 * ((a * gain).clip(0, 255) / 255.0) ** 0.92
    return Image.fromarray(a.clip(0, 255).astype(np.uint8))


def main(src, dst):
    os.makedirs(dst, exist_ok=True)
    kept, thumbs = 0, []
    for f in sorted(glob.glob(os.path.join(src, "*.png"))):
        img = Image.open(f).convert("RGB")
        a = np.asarray(img)
        if has_menu(a):
            print("menu, skipped:", os.path.basename(f))
            continue
        t = np.asarray(img.convert("L").resize((64, 36)), dtype=np.float32)
        if any(np.abs(t - u).mean() < 6 for u in thumbs):
            continue
        thumbs.append(t)
        kept += 1
        out = os.path.join(dst, "w%02d.jpg" % kept)
        clean(img).save(out, quality=86, optimize=True, progressive=False)
        print("kept", os.path.basename(f), "->", os.path.basename(out))
    print("total", kept)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
