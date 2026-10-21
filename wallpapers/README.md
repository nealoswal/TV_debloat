# Wallpapers from your TV's screensaver

Google's **Backdrop** screensaver (Ambient Mode) streams curated photos and stores them
where other apps can't read them. You can still capture what it shows on *your own* TV
and use those captures as Glass Home wallpapers.

> These are licensed photos credited to their photographers. Use them on your own TV only.
> **Don't commit or share them.** `.gitignore` already keeps `launcher/assets/wall/*.jpg` out of git.

## Capture

Start the screensaver, then grab a frame every 30 seconds while it rotates photos.
Don't press OK: on Backdrop 2.x that opens the "Switch Backdrop" menu.

```bash
adb shell am start -n com.android.systemui/.Somnambulator
mkdir -p raw
for i in $(seq -w 1 20); do
  adb shell screencap -p /sdcard/s.png && adb pull /sdcard/s.png raw/q$i.png
  sleep 30
done
adb shell input keyevent KEYCODE_BACK
```

## Clean

[`clean.py`](clean.py) (needs Pillow and NumPy) does three things:
- drops duplicates and frames showing the menu
- removes the clock, weather and credit overlay in the bottom-right corner by mirroring the
  neighbouring ground into it with feathered edges
- undoes the screensaver's dimming

```bash
python clean.py raw ../launcher/assets/wall
```

Then rebuild the launcher. The overlay coordinates are for a 1920×1080 capture of Backdrop 2.2.
