"""Merge booth numbers + map positions into app/src/main/assets/vendors.json.

The expo publishes the booth map only as an image, so these coordinates were read off
app/src/main/res/drawable/booth_map.png by eye: x/y are fractions of that image (0-1), pointing at
each vendor's table. If the map is ever redrawn, re-read them (overlay a % grid on the png) and
edit the table below.

Run after tools/scrape_swatches.py:  python tools/booths.py
"""
import json
import os
import re
import unicodedata

ASSETS = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets")

# vendor name -> (table number, x fraction, y fraction) on booth_map.png
BOOTHS = {
    "Atomic Polish": (1, 0.767, 0.345),
    "Rogue Lacquer": (2, 0.767, 0.440),
    "Jen & Berries": (3, 0.767, 0.550),
    "Zombie Claw": (4, 0.767, 0.635),
    "Sweet & Sour Lacquer": (5, 0.767, 0.725),
    "Waabooz Cosmetics": (6, 0.675, 0.760),
    "Glisten & Glow": (7, 0.587, 0.760),
    "Cupcake Polish": (8, 0.510, 0.760),
    "Beaux Rêves Lacquer": (9, 0.417, 0.772),
    "Polish Pickup": (10, 0.198, 0.830),
    "Hearts and Promises": (11, 0.147, 0.725),
    "Victorian Varnish": (12, 0.111, 0.630),
    "Copacetic Cosmetics": (13, 0.120, 0.490),
    "Arcana Lacquer": (14, 0.194, 0.422),
    "Manimod": (15, 0.262, 0.395),
    "Lurid Lacquer": (16, 0.377, 0.397),
    "KBShimmer": (17, 0.444, 0.275),
    "Polish & Beauty Expo": (18, 0.556, 0.274),
    "Glitter Unique": (19, 0.654, 0.274),
    "Aura Bloom Polish": (20, 0.714, 0.274),
    "Garden Path Lacquers": (21, 0.707, 0.360),
    "Bluebird Lacquer": (22, 0.707, 0.450),
    "Clionadh Cosmetics": (23, 0.575, 0.463),
    "Sassy Sauce Polish": (24, 0.498, 0.463),
    "Dark Moon Esscentuals": (25, 0.464, 0.465),
    "Envy Lacquer": (26, 0.459, 0.359),
    "Ribbits Stickits": (27, 0.498, 0.358),
    "Pinnacle Polish": (28, 0.555, 0.358),
    "STELLA CHROMA": (29, 0.615, 0.358),
    "Alchemy Lacquers": (30, 0.709, 0.580),
    "Swamp Gloss": (31, 0.709, 0.665),
    "Twinkle Hex Polish": (33, 0.567, 0.665),
    "BCB Lacquers": (34, 0.461, 0.677),
    "1422 Designs": (35, 0.464, 0.567),
    "Dew Nail Polish": (36, 0.510, 0.560),
    "Dreamland Lacquer": (37, 0.545, 0.560),
    "Red Eyed Lacquer": (38, 0.603, 0.560),
    "Tyler's Trinkets": (39, 0.378, 0.480),
    "Monarch Lacquer": (40, 0.377, 0.665),
    "Raven Lacquer": (41, 0.341, 0.715),
    "Botanique by Monae": (42, 0.299, 0.752),
    "Broken Pixel Polish": (43, 0.255, 0.747),
    "Peculiar Polish": (44, 0.228, 0.690),
    "PI Colors": (45, 0.199, 0.617),
    "Psyche Minerals": (46, 0.191, 0.560),
    "Snacker Lacquer": (47, 0.240, 0.505),
    "Prism Parade": (48, 0.299, 0.487),
}


def norm(name):
    plain = unicodedata.normalize("NFKD", name or "").encode("ascii", "ignore").decode()
    return re.sub(r"[^a-z0-9]", "", plain.lower())


def main():
    path = os.path.join(ASSETS, "vendors.json")
    with open(path, encoding="utf-8") as f:
        vendors = json.load(f)

    booths = {norm(k): v for k, v in BOOTHS.items()}
    placed = 0
    for v in vendors:
        booth = booths.get(norm(v["name"]))
        if booth:
            v["booth"], v["boothX"], v["boothY"] = booth
            placed += 1
        else:
            v.pop("booth", None)
            print("no booth on the map:", v["name"])

    with open(path, "w", encoding="utf-8") as f:
        json.dump(vendors, f, indent=1, ensure_ascii=False)
    print(f"{placed}/{len(vendors)} vendors placed on the booth map")


if __name__ == "__main__":
    main()
