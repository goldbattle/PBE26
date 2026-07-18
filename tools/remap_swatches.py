"""Keep saved favourites pointing at the same swatch after an Airtable update.

The app stores a favourite as an asset path ("swatches/<slug>/07.jpg"), so a slot must keep
meaning the same polish forever. tools/scrape_swatches.py numbers slots by Airtable order, which
shifts whenever someone inserts, removes or re-uploads a row -- so a fresh scrape is internally
correct but renumbered, and every favourite would silently point at a different polish.

This re-slots the fresh scrape against the last committed state (git HEAD is the reference: it is
what shipped in the last APK) and appends only genuinely new swatches at the end. Runs
automatically at the end of scrape_swatches.py; run it directly to preview or re-check:

    python tools/remap_swatches.py [--dry-run]

Swatches deleted from the Airtable do lose their slot -- nothing can be done there, and the app
just stops listing that favourite.
"""
import hashlib
import io
import json
import os
import re
import subprocess
import sys

from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), "..")
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
SWATCH_DIR = os.path.join(ASSETS, "swatches")
IDS_DIR = os.path.join(ROOT, "tools", "swatch_ids")
VENDORS = os.path.join(ASSETS, "vendors.json")


def git_show(path):
    r = subprocess.run(["git", "-C", ROOT, "show", f"HEAD:{path}"], capture_output=True)
    return r.stdout if r.returncode == 0 else None


def digest(b):
    return hashlib.sha256(b).hexdigest()


def dhash(b, size=8):
    """Perceptual hash: Airtable re-encodes some uploads, so identical polishes differ byte-wise.

    Row-gradient hash -- compares each pixel to its right neighbour, which survives re-compression
    and rescaling but still separates two different polishes.
    """
    img = Image.open(io.BytesIO(b)).convert("L").resize((size + 1, size), Image.LANCZOS)
    px = img.tobytes()
    bits = 0
    for r in range(size):
        for c in range(size):
            bits = bits << 1 | (px[r * (size + 1) + c] > px[r * (size + 1) + c + 1])
    return bits


DHASH_MAX = 6  # bits of difference still counted as the same image, out of 64


def key(name):
    return re.sub(r"[^a-z0-9]", "", (name or "").lower())


def remap(old, new, hash_fn=dhash):
    """old/new: {slot: (name, bytes)}. -> {new slot: slot it should live at}.

    Three passes, strongest identity first:
      1. identical swatch name -- the polish is what the user favourited, so it keeps its slot
         even when the vendor re-shot the photo entirely (this is the common case here)
      2. identical bytes -- an untouched, unnamed attachment
      3. near-identical image -- an unnamed upload Airtable merely re-encoded
    Whatever is left is genuinely new and appends past every old slot.
    """
    plan, taken = {}, set()

    for ident in (lambda name, data: key(name), lambda name, data: digest(data)):
        table = {}
        for slot, (name, data) in old.items():
            k = ident(name, data)
            if slot not in taken and k:  # a blank name identifies nothing
                table.setdefault(k, slot)
        for slot in sorted(new):
            o = table.get(ident(*new[slot]))
            if slot not in plan and o is not None and o not in taken:
                plan[slot] = o
                taken.add(o)

    left_old = {s: hash_fn(d) for s, (_, d) in old.items() if s not in taken}
    left_new = {s: hash_fn(new[s][1]) for s in sorted(new) if s not in plan}
    # Greedy over every pairing cheapest-first: the sets are tens of images, so an optimal
    # assignment would not pay for itself.
    # ponytail: O(n^2 log n) greedy, swap in scipy's Hungarian if a vendor ever has thousands.
    pairs = sorted((bin(h1 ^ h2).count("1"), n, o) for n, h1 in left_new.items()
                   for o, h2 in left_old.items())
    for dist, n, o in pairs:
        if dist <= DHASH_MAX and n not in plan and o not in taken:
            plan[n] = o
            taken.add(o)

    nxt = max([*old, *taken, -1]) + 1  # genuinely new images append past every old slot
    for slot in sorted(new):
        if slot not in plan:
            plan[slot] = nxt
            nxt += 1
    return plan


def main():
    dry = "--dry-run" in sys.argv
    vendors = json.load(open(VENDORS, encoding="utf-8"))
    old_vendors = {v["name"]: v for v in json.loads(git_show("app/src/main/assets/vendors.json"))}
    moved = kept = added = 0

    # Key by the slot the file already sits in, not by list position -- once a slot is vacated the
    # two diverge, and only the slot is what a saved favourite actually points at.
    def slot_of(s):
        return int(os.path.basename(s["file"])[:2])

    for v in vendors:
        # Swatches and the shopping-list/merch images live in their own folders and their own
        # favourite list, so they re-slot independently of each other.
        for field, ids_name in (("swatches", "{}.json"), ("extras", "{}-extras.json")):
            if not v.get(field):
                continue
            slug = v["slug"]

            new_files = {}
            for s in v[field]:
                with open(os.path.join(ASSETS, s["file"]), "rb") as f:
                    new_files[slot_of(s)] = (s["name"], f.read())

            old_files = {}
            for s in old_vendors.get(v["name"], {}).get(field, []):
                blob = git_show(f"app/src/main/assets/{s['file']}")
                if blob:
                    old_files[slot_of(s)] = (s["name"], blob)

            plan = remap(old_files, new_files)
            if all(a == b for a, b in plan.items()):
                kept += len(plan)
                continue

            ids_path = os.path.join(IDS_DIR, ids_name.format(slug))
            ids = json.load(open(ids_path)) if os.path.exists(ids_path) else {}
            by_slot = {slot_of(s): s for s in v[field]}
            out, items, new_ids = os.path.join(ASSETS, field, slug), {}, {}
            for src, dst in plan.items():
                items[dst] = {"name": by_slot[src]["name"],
                              "file": f"{field}/{slug}/{dst:02d}.jpg"}
                new_ids[f"{dst:02d}"] = ids.get(f"{src:02d}", "")
                moved += src != dst
                added += dst >= len(old_files)
                if not dry:
                    with open(os.path.join(out, f".{dst:02d}.tmp"), "wb") as f:
                        f.write(new_files[src][1])

            v[field] = [items[k] for k in sorted(items)]
            print(f"{v['name']} ({field}): {len(old_files)} old -> {len(new_files)} new, "
                  f"{sum(1 for a, b in plan.items() if a != b)} re-slotted")
            if dry:
                continue
            for f in os.listdir(out):  # drop slots that no longer exist
                if f.endswith(".jpg"):
                    os.remove(os.path.join(out, f))
            for f in os.listdir(out):
                if f.endswith(".tmp"):
                    os.rename(os.path.join(out, f), os.path.join(out, f[1:-4] + ".jpg"))
            json.dump(new_ids, open(ids_path, "w"))

    if not dry:
        with open(VENDORS, "w", encoding="utf-8") as f:
            json.dump(vendors, f, indent=1, ensure_ascii=False)
    print(f"\n{kept} already in place, {moved} re-slotted, {added} appended{' (dry run)' if dry else ''}")


def demo():
    a, b, c = b"a", b"b", b"c"
    # stand-in for dhash: one int per image, so "near" means a low bit distance
    h = {a: 0b0000, b: 0b1111_1111, c: 0b1111_0000_1111, b"a2": 0b0001}.get
    A, B, C = ("Delulu", a), ("Sus", b), ("Bruce", c)

    # unchanged set stays put
    assert remap({0: A, 1: B}, {0: A, 1: B}, h) == {0: 0, 1: 1}
    # a row inserted at the top must not steal slot 0 from the swatch already there
    assert remap({0: A, 1: B}, {0: C, 1: A, 2: B}, h) == {0: 2, 1: 0, 2: 1}
    # a removed swatch leaves its slot empty rather than shifting the survivors
    assert remap({0: A, 1: B, 2: C}, {0: A, 1: C}, h) == {0: 0, 1: 2}
    # brand new vendor: everything appends from 0
    assert remap({}, {0: A, 1: B}, h) == {0: 0, 1: 1}
    # the Zombie Claw case: same polish, completely re-shot photo -> the NAME holds the slot
    assert remap({0: A, 1: B}, {0: ("Sus", c), 1: ("Delulu", b)}, h) == {0: 1, 1: 0}
    # unnamed re-encoded upload falls through to image similarity
    assert remap({0: ("", a), 1: ("", b)}, {0: ("", b), 1: ("", b"a2")}, h) == {0: 1, 1: 0}
    # two different unnamed swatches must not match each other
    assert remap({0: ("", a)}, {0: ("", b)}, h) == {0: 1}
    # blank names never collide with one another
    assert remap({0: ("", a), 1: ("", b)}, {0: ("", c)}, h) == {0: 2}
    print("ok")


if __name__ == "__main__":
    demo() if "--demo" in sys.argv else main()
