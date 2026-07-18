"""Pull the community Airtable (swatches, brand blurbs, booth map) into the app's offline assets.

Run AFTER tools/scrape_vendors.py -- it adds swatch/blurb fields onto the vendors.json that
script writes:

    python tools/scrape_vendors.py
    python tools/scrape_swatches.py

Writes:
    app/src/main/assets/swatches/<slug>/NN.jpg   one image per polish
    app/src/main/assets/extras/<slug>/NN.jpg     shopping list first, then merch
    app/src/main/assets/vendors.json             + swatches[], extras[], swatchers[], airtableInfo
    app/src/main/assets/maps/booth_map.png       the expo's booth map, only if we don't have it yet

Finishes by running tools/remap_swatches.py, which keeps already-saved favourites pointing at the
swatch they were saved for even when the Airtable re-orders or re-photographs its rows, and then
tools/booths.py, which puts back the booth numbers scrape_vendors.py just overwrote.

Needs: python 3, ImageMagick (`magick`) on PATH.
"""
import gzip
import json
import unicodedata
import os
import random
import re
import string
import subprocess
import time
import urllib.parse
import urllib.request

import booths
import remap_swatches

SHARE = "https://airtable.com/embed/appHCStOEjlIqVBea/shrPc6rx46s6keM1T"
ROOT = os.path.join(os.path.dirname(__file__), "..")
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
SWATCH_DIR = os.path.join(ASSETS, "swatches")
EXTRAS_DIR = os.path.join(ASSETS, "extras")
MAP_PNG = os.path.join(ASSETS, "maps", "booth_map.png")
IDS_DIR = os.path.join(ROOT, "tools", "swatch_ids")

# The expo's own row (Brand = "Polish & Beauty Expo"). Its Swatches cell holds, in order:
#   [0] accepted-payment-methods chart, [1] THE BOOTH MAP.
MAP_ROW = "recaE4HPvt8uhq5UT"
MAP_ATTACHMENT = 1

SWATCH_WIDTH = 600  # px; keeps 400+ images to a sane APK size


def fetch(req, tries=5):
    """Airtable resets the connection when we pull a few hundred images; back off and retry."""
    for n in range(tries):
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                data = r.read()
            return gzip.decompress(data) if data[:2] == b"\x1f\x8b" else data
        except Exception:
            if n == tries - 1:
                raise
            time.sleep(2 ** n)


def get(url, headers=None):
    return fetch(urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0", **(headers or {})}))


def fetch_view():
    """Airtable's share page carries the tokens its own JS uses to read the view."""
    page = get(SHARE).decode("utf-8", "replace")
    i = page.index('"accessPolicy":') + len('"accessPolicy":')
    access_policy, _ = json.JSONDecoder().raw_decode(page[i:])
    app = re.search(r'"applicationId":"(app\w+)"', page).group(1)
    view = re.search(r'"sharedViewId":"(viw\w+)"', page).group(1)

    q = urllib.parse.urlencode({
        "stringifiedObjectParams": "{}",
        "requestId": "req" + "".join(random.choices(string.ascii_letters + string.digits, k=14)),
        "accessPolicy": access_policy,
    })
    raw = get(
        f"https://airtable.com/v0.3/view/{view}/readSharedViewData?{q}",
        {
            "accept": "application/json",
            "x-airtable-application-id": app,
            "x-time-zone": "America/Chicago",
            "x-user-locale": "en",
            "x-requested-with": "XMLHttpRequest",
        },
    )
    return json.loads(raw)["data"], Signer(view, app, access_policy)


def rich_text(cell):
    """Airtable rich text -> plain text."""
    if not cell:
        return ""
    parts = [op.get("insert") for op in cell.get("documentValue", [])]
    return "".join(p for p in parts if isinstance(p, str)).strip()


def people(cell):
    """The "Owner/Swatcher info" cell: one name per line, sometimes two joined by "&"."""
    return [n.strip() for n in re.split(r"[\n&]+", cell or "") if n.strip()]


def norm(name):
    """Fold accents too: the site writes "Beaux Rêves", the Airtable writes "Beaux Reves"."""
    plain = unicodedata.normalize("NFKD", name or "").encode("ascii", "ignore").decode()
    return re.sub(r"[^a-z0-9]", "", plain.lower())


def match_vendor(brand, vendors):
    """Airtable brand names are looser than the site's ("Arcana" vs "Arcana Lacquer")."""
    b = norm(brand)
    for v in vendors:
        if norm(v["name"]) == b:
            return v
    for v in vendors:
        n = norm(v["name"])
        if n.startswith(b) or b.startswith(n):
            return v
    return None


class Signer:
    """Attachment URLs 403 unless signed.

    The view payload only signs its first ~1000 urls, which runs out partway down the table, so
    ask for signatures per row -- the same call the Airtable web client makes when it lazily
    renders a cell.
    """

    def __init__(self, view, app, access_policy):
        self.view, self.app, self.access_policy = view, app, access_policy

    def sign_row(self, row_id, column_ids):
        """Signed urls for one row's attachments -> {unsignedUrl: signedUrl}."""
        body = urllib.parse.urlencode({
            "stringifiedObjectParams": json.dumps({"columnIdsOrNullByRowId": {row_id: column_ids}}),
            "requestId": "req" + "".join(random.choices(string.ascii_letters + string.digits, k=14)),
            "accessPolicy": self.access_policy,
        }).encode()
        req = urllib.request.Request(
            f"https://airtable.com/v0.3/view/{self.view}/readSignedAttachmentUrls",
            data=body,
            method="POST",
            headers={
                "User-Agent": "Mozilla/5.0",
                "accept": "application/json",
                "content-type": "application/x-www-form-urlencoded; charset=UTF-8",
                "x-airtable-application-id": self.app,
                "x-time-zone": "America/Chicago",
                "x-user-locale": "en",
                "x-requested-with": "XMLHttpRequest",
            },
        )
        return json.loads(fetch(req))["data"]


def save_image(url, dst, width=SWATCH_WIDTH):
    with open(dst, "wb") as f:
        f.write(get(url, {"referer": "https://airtable.com/"}))
    subprocess.run(
        ["magick", dst, "-resize", f"{width}x{width}>", "-strip", "-quality", "80", dst],
        check=True,
    )


def pull(attachments, signed, out_dir, ids_path, rel):
    """Download one cell's attachments into out_dir as NN.jpg -> [{name, file}].

    Slots are positional and an ids sidecar records which attachment each one holds, so a
    re-ordered Airtable re-fetches the slots that actually changed instead of leaving NN.jpg
    holding the previous run's picture under a new name.
    """
    os.makedirs(out_dir, exist_ok=True)
    cached = json.load(open(ids_path)) if os.path.exists(ids_path) else {}
    items = []
    for i, att in enumerate(attachments):
        name = os.path.splitext(att.get("filename") or "")[0]
        if name.lower() in ("image", ""):  # unnamed upload
            name = ""
        dst = os.path.join(out_dir, f"{i:02d}.jpg")
        stale = cached.get(f"{i:02d}") != att["id"]
        if stale or not os.path.exists(dst) or os.path.getsize(dst) == 0:  # resumable
            try:
                save_image(signed.get(att["url"], att["url"]), dst)
            except Exception as e:
                print("  failed:", rel, i, e)
                continue
        cached[f"{i:02d}"] = att["id"]
        items.append({"name": name, "file": f"{rel}/{i:02d}.jpg"})
    json.dump(cached, open(ids_path, "w"))
    return items


def main():
    data, signer = fetch_view()
    cols = {c["name"]: c["id"] for c in data["columns"]}
    swatch_col = cols["Swatches"]
    image_cols = [swatch_col, cols["Shopping List"], cols["Merch"]]

    vendors_path = os.path.join(ASSETS, "vendors.json")
    with open(vendors_path, encoding="utf-8") as f:
        vendors = json.load(f)

    os.makedirs(SWATCH_DIR, exist_ok=True)
    os.makedirs(IDS_DIR, exist_ok=True)

    unmatched, total = [], 0
    for row in data["rows"]:
        cell = row["cellValuesByColumnId"]
        brand = cell.get(cols["Brand"]) or ""
        attachments = cell.get(swatch_col) or []

        if row["id"] == MAP_ROW:
            # The shipped map is edited (feathered edges) and georeferenced against its exact pixel
            # size, so never overwrite it. Delete the file to pull a fresh one -- and then re-align.
            if os.path.exists(MAP_PNG):
                print("booth map: keeping the aligned copy on disk")
            elif len(attachments) > MAP_ATTACHMENT:
                att = attachments[MAP_ATTACHMENT]
                signed = signer.sign_row(row["id"], [swatch_col])
                os.makedirs(os.path.dirname(MAP_PNG), exist_ok=True)
                save_image(signed.get(att["url"], att["url"]), MAP_PNG, width=2400)
                print("booth map ->", os.path.relpath(MAP_PNG, ROOT))
            continue

        vendor = match_vendor(brand, vendors)
        if not vendor:
            unmatched.append(brand)
            continue

        vendor["airtableInfo"] = rich_text(cell.get(cols["Information"]))
        vendor["website"] = cell.get(cols["Website"]) or vendor.get("website", "")
        vendor["swatchers"] = people(cell.get(cols["Owner/Swatcher info"]))

        slug = vendor["slug"]
        # The shopping list is what the brand is bringing, so it leads; merch follows it.
        extra_atts = (cell.get(cols["Shopping List"]) or []) + (cell.get(cols["Merch"]) or [])
        signed = signer.sign_row(row["id"], image_cols) if attachments or extra_atts else {}

        vendor["swatches"] = pull(
            attachments, signed, os.path.join(SWATCH_DIR, slug),
            os.path.join(IDS_DIR, f"{slug}.json"), f"swatches/{slug}",
        )
        vendor["extras"] = pull(
            extra_atts, signed, os.path.join(EXTRAS_DIR, slug),
            os.path.join(IDS_DIR, f"{slug}-extras.json"), f"extras/{slug}",
        )
        total += len(vendor["swatches"])
        print(f"{brand}: {len(vendor['swatches'])} swatches, {len(vendor['extras'])} extras")

    for v in vendors:
        v.setdefault("swatches", [])
        v.setdefault("extras", [])
        v.setdefault("swatchers", [])
        v.setdefault("airtableInfo", "")
        v.setdefault("website", "")

    with open(vendors_path, "w", encoding="utf-8") as f:
        json.dump(vendors, f, indent=1, ensure_ascii=False)

    print(f"\n{total} swatches for {sum(1 for v in vendors if v['swatches'])} vendors, "
          f"{sum(len(v.get('extras', [])) for v in vendors)} shopping-list/merch images")
    if unmatched:
        print("no site vendor matched:", unmatched)

    # Slots above are numbered by Airtable order, which moves between updates. Put every swatch
    # back in the slot it shipped in, so the favourites people already saved still resolve.
    print()
    remap_swatches.main()

    # scrape_vendors.py rewrites vendors.json from the website, which knows nothing about booths --
    # so re-merge them here rather than leaving every pin on the map silently gone.
    print()
    booths.main()


if __name__ == "__main__":
    main()
