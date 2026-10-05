#!/usr/bin/env python3
"""Screenshots of PBE26 with demo data, and a Screenshots section in the README built from them.

    python tools/screenshots.py              # build, install, capture, update README
    python tools/screenshots.py --no-build   # reuse the last APK
    python tools/screenshots.py --avd pbe26  # which emulator to boot if none is running

Needs an Android 13+ emulator and ImageMagick (`magick`) to shrink the images; without it
the full-size PNGs are kept. It WIPES the app's data on the device it runs on, so point it
at an emulator, not your phone.
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "docs" / "screenshots"
PKG = "com.aria.pbe26"
EXE = ".exe" if os.name == "nt" else ""


def sdk_dir() -> Path:
    props = ROOT / "local.properties"
    if props.exists():
        for line in props.read_text().splitlines():
            if line.startswith("sdk.dir="):
                return Path(line.split("=", 1)[1].replace("\\\\", "\\").replace("\\:", ":"))
    env = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if env:
        return Path(env)
    sys.exit("Android SDK not found: set sdk.dir in local.properties or ANDROID_SDK_ROOT")


SDK = sdk_dir()
ADB = str(SDK / "platform-tools" / f"adb{EXE}")


def adb(*args: str, check: bool = True) -> str:
    r = subprocess.run([ADB, *args], capture_output=True, text=True, encoding="utf-8", errors="replace")
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)}: {r.stderr.strip()}")
    return r.stdout


def shell(cmd: str, check: bool = True) -> str:
    return adb("shell", cmd, check=check)


# ---------- Device ----------

def ensure_device(avd: str) -> None:
    if re.search(r"\tdevice$", adb("devices"), re.M):
        return
    print(f"Booting emulator {avd}...")
    env = dict(os.environ, ANDROID_SDK_ROOT=str(SDK))
    subprocess.Popen(
        [str(SDK / "emulator" / f"emulator{EXE}"), "-avd", avd, "-no-snapshot-save", "-no-audio", "-no-boot-anim"],
        env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    for _ in range(120):
        time.sleep(5)
        if shell("getprop sys.boot_completed", check=False).strip() == "1":
            time.sleep(10)  # let the launcher settle
            return
    sys.exit("Emulator didn't boot in 10 minutes")


def build_and_install(build: bool) -> None:
    if build:
        gradlew = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
        print("Building release APK...")
        subprocess.run([str(gradlew), "assembleRelease", "-q"], cwd=ROOT, check=True)
    apk = ROOT / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"
    print("Installing...")
    adb("install", "-r", str(apk))


# ---------- UI driving ----------

def nodes() -> list[tuple[str, int, int]]:
    """Visible texts (and content descriptions) with their center points."""
    shell("uiautomator dump /sdcard/ui.xml", check=False)
    xml = shell("cat /sdcard/ui.xml", check=False)
    out = []
    for m in re.finditer(r'(?:text|content-desc)="([^"]+)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        x1, y1, x2, y2 = map(int, m.groups()[1:])
        out.append((m.group(1), (x1 + x2) // 2, (y1 + y2) // 2))
    return out


def tap_text(text: str, prefix: bool = False, last: bool = False, timeout: float = 10) -> bool:
    """Taps the element showing [text]. [last] picks the lowest match (the tab bar, not the page title)."""
    end = time.time() + timeout
    while time.time() < end:
        hits = [n for n in nodes() if (n[0].startswith(text) if prefix else n[0] == text)]
        if hits:
            _, x, y = max(hits, key=lambda n: n[2]) if last else hits[0]
            shell(f"input tap {x} {y}")
            time.sleep(1.5)
            return True
        time.sleep(1)
    print(f"  ! couldn't find '{text}'")
    return False


def tab(name: str) -> None:
    tap_text(name, last=True)


def back() -> None:
    shell("input keyevent 4")
    time.sleep(1.5)


def scroll(dy: int = 900) -> None:
    shell(f"input swipe 540 1700 540 {1700 - dy} 500")
    time.sleep(1.2)


def shot(name: str) -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    path = OUT / f"{name}.png"
    with open(path, "wb") as f:
        subprocess.run([ADB, "exec-out", "screencap", "-p"], stdout=f, check=True)
    magick = shutil.which("magick")
    if magick:  # README-sized: about 430 px wide
        subprocess.run([magick, str(path), "-resize", "40%", "-strip", str(path)], check=False)
    print(f"  saved {path.relative_to(ROOT)}")


# ---------- Example data ----------

def demo_backup() -> dict:
    """Generate demo save data with favorites and journal entries."""
    # Vendor data: create a few popular vendors with starred status
    vendors_data = {
        "starred": [
            "Essie",
            "OPI",
            "Sally Hansen",
        ],
        "favorites": [
            "ESSIE-001",  # Some swatch IDs as examples
            "OPI-042",
            "ORLY-015",
            "CHINA-GLAZE-023",
        ],
        "notes": "Sample notes about polishes and vendors",
    }

    # Journal entries for the info tab
    journal_entries = {
        "2026-07-18": "Opening day! Can't wait to see all the vendors. Starting with the front rows.",
        "2026-07-19": "Second day - found some amazing new shades! Need to check out more booths.",
    }

    return {
        "app": "pbe26",
        "version": 1,
        "vendors": vendors_data,
        "journal": journal_entries,
    }


def share_text(text: str) -> None:
    """Shares [text] into the app, as another app's share sheet would."""
    tmp = ROOT / "app" / "build" / "share.txt"
    tmp.parent.mkdir(parents=True, exist_ok=True)
    tmp.write_text(text, encoding="utf-8")
    adb("push", str(tmp), "/data/local/tmp/share.txt")
    shell(f'am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "$(cat /data/local/tmp/share.txt)" -n {PKG}/.MainActivity')
    time.sleep(4)


# ---------- README ----------

CAPTIONS = [
    ("info", "Info", "Event dates, venue details, and journal entries you can edit."),
    ("vendors", "Vendors", "Browse 48 vendors, search by name, see all their polishes."),
    ("vendor_detail", "Vendor details", "Full vendor info with photo gallery and what to buy notes."),
    ("favorites", "Favorites", "Heart vendors and polishes to save them in your collection."),
    ("map", "Map", "Live floorplan with booth locations and georeferenced overlays."),
    ("saved", "Saved", "Your favorite vendors and polishes in one place."),
]

# Only these make the README; the rest are still captured for reference
README_SELECT = ["info", "vendors", "map", "saved"]


def update_readme() -> None:
    have = [n for n in README_SELECT if (OUT / f"{n}.png").exists()]
    imgs = " ".join(f'<img src="docs/screenshots/{n}.png" width="200" alt="{n}">' for n in have)
    section = "\n".join([
        "<!-- screenshots:start -->",
        f'<p align="center">{imgs}</p>',
        "<!-- screenshots:end -->",
    ])
    readme = ROOT / "README.md"
    text = readme.read_text(encoding="utf-8")
    if "<!-- screenshots:start -->" in text:
        text = re.sub(r"<!-- screenshots:start -->.*?<!-- screenshots:end -->", lambda _: section, text, flags=re.S)
    else:  # after the intro paragraph
        head, sep, rest = text.partition("\n## ")
        text = head.rstrip() + "\n\n" + section + "\n\n" + sep.lstrip("\n") + rest if sep else text + "\n\n" + section + "\n"
    readme.write_text(text, encoding="utf-8")
    print("README.md updated")


# ---------- Tour ----------

def tour() -> None:
    print("Info tab")
    tab("Info")
    time.sleep(2)
    shot("info")
    scroll(600)
    time.sleep(1)

    print("Vendors tab")
    tab("Vendors")
    time.sleep(2)
    shot("vendors")

    # Tap a vendor to show detail
    if tap_text("Essie", prefix=True):
        time.sleep(2)
        shot("vendor_detail")
        scroll(800)
        time.sleep(1)
        back()

    # Search for vendors with favorites
    print("Searching vendors")
    tap_text("Search", prefix=True)
    time.sleep(1)
    shell("input text OPI")
    time.sleep(1)
    back()

    # Show favorites
    if tap_text("Favorites", prefix=True):
        time.sleep(2)
        shot("favorites")
        back()

    print("Map tab")
    tab("Map")
    time.sleep(3)
    shot("map")
    scroll(600)
    time.sleep(1)

    print("Saved tab")
    tab("Saved")
    time.sleep(2)
    shot("saved")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--avd", default="pbe26", help="emulator to boot if no device is connected")
    ap.add_argument("--no-build", action="store_true", help="install the existing release APK")
    ap.add_argument("--no-readme", action="store_true", help="don't touch README.md")
    args = ap.parse_args()

    ensure_device(args.avd)
    build_and_install(not args.no_build)

    print("Resetting the app and loading example data...")
    shell(f"pm clear {PKG}", check=False)
    shell(f"am start -n {PKG}/.MainActivity")
    time.sleep(4)
    backup = demo_backup()
    share_text(json.dumps(backup))
    time.sleep(2)

    tour()
    if not args.no_readme:
        update_readme()


if __name__ == "__main__":
    main()
