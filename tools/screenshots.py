#!/usr/bin/env python3
"""Screenshots of PBE26's four tabs, with a few vendors starred and swatches hearted so Saved isn't empty.

    python tools/screenshots.py              # build, install, capture
    python tools/screenshots.py --no-build   # reuse the last APK
    python tools/screenshots.py --avd pbe26  # which emulator to boot if none is running

Needs an Android 13+ emulator and ImageMagick (`magick`) to shrink the images; without it
the full-size PNGs are kept. It WIPES the app's data on the device it runs on, so point it
at an emulator, not your phone.
"""
import argparse
import os
import re
import shutil
import subprocess
import sys
import time
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


# ---------- Tour ----------

def tap_each(desc: str, picks: list[int]) -> None:
    """Taps the [picks]-th on-screen elements labelled [desc], top to bottom."""
    hits = sorted((n for n in nodes() if n[0] == desc), key=lambda n: n[2])
    for i in picks:
        if i < len(hits):
            shell(f"input tap {hits[i][1]} {hits[i][2]}")
            time.sleep(0.8)


def tour() -> None:
    print("Info")
    tab("Info")
    time.sleep(2)
    shot("info")

    print("Vendors (starring a few)")
    tab("Vendors")
    time.sleep(2)
    tap_each("Bookmark", [0, 2, 3])
    scroll(1200)
    tap_each("Bookmark", [1, 3])
    scroll(-1200)
    scroll(-1200)
    shot("vendors")

    print("Hearting swatches")
    for vendor, picks in [("BCB Lacquers", [0, 3, 4]), ("Atomic Polish", [1, 2])]:
        if tap_text(vendor):
            time.sleep(2)
            scroll(900)  # the swatch grid starts below the fold
            tap_each("Save this swatch", picks)
            back()

    print("Map")
    tab("Map")
    time.sleep(4)
    shot("map")

    print("Saved")
    tab("Saved")
    time.sleep(2)
    shot("saved")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--avd", default="pbe26", help="emulator to boot if no device is connected")
    ap.add_argument("--no-build", action="store_true", help="install the existing release APK")
    args = ap.parse_args()

    ensure_device(args.avd)
    build_and_install(not args.no_build)

    print("Resetting the app...")
    shell(f"pm clear {PKG}", check=False)
    shell(f"am start -n {PKG}/.MainActivity")
    time.sleep(4)
    tour()


if __name__ == "__main__":
    main()
