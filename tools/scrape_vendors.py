"""Rebuild app/src/main/assets/vendors.json + assets/vendors/*.jpg from the expo site.

Run when the vendor list changes:  python tools/scrape_vendors.py
Needs: python 3, ImageMagick (`magick`) on PATH.
"""
import gzip
import html
import json
import os
import re
import subprocess
import urllib.request

LIST_URL = "https://polishandbeautyexpo.com/more-about-vendors/"
ASSETS = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets")
IMG_DIR = os.path.join(ASSETS, "vendors")

# paragraphs on a vendor page that are site furniture, not the vendor's bio
SKIP = ("Polish & Beauty Expo is an annual", "Sign Up", "Copyright", "All Rights Reserved",
        "const ", "document.", "function", "window.")


def get(url):
    """Fetch bytes. The site gzips even when we don't ask, so decompress if needed."""
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=30) as r:
        data = r.read()
    if data[:2] == b"\x1f\x8b":
        data = gzip.decompress(data)
    return data


def text_of(page):
    """Vendor bio: the first few real paragraphs of the page."""
    out = []
    for m in re.finditer(r"<p[^>]*>(.*?)</p>", page, re.S):
        t = " ".join(html.unescape(re.sub(r"<[^>]+>", "", m.group(1))).split())
        if len(t) < 40 or any(s in t for s in SKIP):
            continue
        out.append(t)
    return "\n\n".join(out[:4])


def parse_list(page):
    """One vendor per `rtin-item` card: name, owner, page, headshot, socials."""
    vendors = []
    for block in page.split('<div class="rtin-item')[1:]:
        m = re.search(r'<h3 class="title">\s*<a href="([^"]+)">([^<]+)</a>', block)
        if not m:
            continue
        d = re.search(r'class="designation">([^<]*)<', block)
        img = re.search(r'src="([^"]+?)-400x400\.(jpe?g|png)"', block)
        socials = {}
        tail = block.split("item-social", 1)
        if len(tail) > 1:
            for url in re.findall(r'href="(https?://[^"]+)"', tail[1]):
                low = url.lower()
                for k in ("instagram", "facebook", "tiktok", "youtube", "pinterest", "twitter", "x.com"):
                    if k in low:
                        socials.setdefault("x" if k in ("twitter", "x.com") else k, url)
                        break
        vendors.append({
            "name": html.unescape(m.group(2)).strip(),
            "owner": html.unescape(d.group(1)).strip() if d else "",
            "page": m.group(1),
            "socials": socials,
            "_img": f"{img.group(1)}-400x400.{img.group(2)}" if img else "",
        })
    seen, out = set(), []
    for v in vendors:  # the page repeats a few cards
        if v["name"] not in seen:
            seen.add(v["name"])
            out.append(v)
    return out


def main():
    os.makedirs(IMG_DIR, exist_ok=True)
    vendors = parse_list(get(LIST_URL).decode("utf-8", "replace"))
    print(f"{len(vendors)} vendors")

    for v in vendors:
        slug = re.sub(r"[^a-z0-9]+", "-", v["name"].lower()).strip("-")
        v["slug"] = slug
        v["bio"] = ""
        v["img"] = ""

        try:
            raw = get(v["page"])
            page = raw.decode("utf-8", "replace")
            if "�" in page:  # some pages are cp1252
                page = raw.decode("cp1252", "replace")
            v["bio"] = text_of(page)
        except Exception as e:
            print("  bio failed:", slug, e)

        src = v.pop("_img")
        if src:
            dst = os.path.join(IMG_DIR, f"{slug}.jpg")
            try:
                with open(dst, "wb") as f:
                    f.write(get(src))
                subprocess.run(["magick", dst, "-resize", "300x300", "-strip", "-quality", "82", dst], check=True)
                v["img"] = f"vendors/{slug}.jpg"
            except Exception as e:
                print("  image failed:", slug, e)

    with open(os.path.join(ASSETS, "vendors.json"), "w", encoding="utf-8") as f:
        json.dump(vendors, f, indent=1, ensure_ascii=False)
    print(f"wrote {sum(1 for v in vendors if v['bio'])} bios, {sum(1 for v in vendors if v['img'])} images")


if __name__ == "__main__":
    main()
