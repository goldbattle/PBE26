"""Manually georeference the floorplan / booth map over OpenStreetMap tiles.

    python tools/align_maps.py

Each layer is stored as: anchor lat/lon of the image centre, rotation (bearing of
the image's "up" direction, degrees clockwise from north) and scale (metres per
image pixel). That's resolution-independent, so the Android side can rebuild the
same overlay from any bitmap size.

Controls (act on the selected layer):
    left-drag       move layer          right-drag / arrows   pan view
    shift+wheel     rotate              ctrl+wheel            scale
    wheel           zoom view           [ ]                   rotate 0.5 deg
    - =             scale 0.5%          s                     save + print
"""
import json
import math
import os
import queue
import threading
import tkinter as tk
import urllib.request
from tkinter import ttk

from PIL import Image, ImageTk

TILE = 256
HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, ".tilecache")
CFG = os.path.join(HERE, "map_align.json")
DRAWABLE = os.path.normpath(os.path.join(HERE, "..", "app", "src", "main", "res", "drawable"))
W, H = 1100, 800
UA = {"User-Agent": "PBE26-align/1.0 (map alignment tool)"}


def latlon_to_world(lat, lon, z):
    n = TILE * 2 ** z
    s = math.sin(math.radians(lat))
    return (lon + 180) / 360 * n, (0.5 - math.log((1 + s) / (1 - s)) / (4 * math.pi)) * n


def world_to_latlon(x, y, z):
    n = TILE * 2 ** z
    return math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * y / n)))), x / n * 360 - 180


def metres_per_worldpx(lat, z):
    return 156543.03392804097 * math.cos(math.radians(lat)) / 2 ** z


class Tiles:
    """OSM tile fetcher: disk cache + background thread, missing tiles draw grey."""

    def __init__(self, on_ready):
        os.makedirs(CACHE, exist_ok=True)
        self.mem, self.pending, self.q = {}, set(), queue.Queue()
        self.on_ready = on_ready
        threading.Thread(target=self._worker, daemon=True).start()

    def get(self, z, x, y):
        key = (z, x, y)
        if key in self.mem:
            return self.mem[key]
        path = os.path.join(CACHE, f"{z}_{x}_{y}.png")
        if os.path.exists(path):
            img = Image.open(path).convert("RGB")
            self.mem[key] = img
            return img
        if key not in self.pending:
            self.pending.add(key)
            self.q.put(key)
        return None

    def _worker(self):
        while True:
            z, x, y = self.q.get()
            path = os.path.join(CACHE, f"{z}_{x}_{y}.png")
            try:
                req = urllib.request.Request(f"https://tile.openstreetmap.org/{z}/{x}/{y}.png", headers=UA)
                with urllib.request.urlopen(req, timeout=10) as r:
                    data = r.read()
                with open(path, "wb") as f:
                    f.write(data)
            except Exception as e:
                print(f"tile {z}/{x}/{y}: {e}")
            self.pending.discard((z, x, y))
            self.on_ready()


class App:
    def __init__(self, root):
        self.root = root
        root.title("PBE26 map alignment")

        saved = json.load(open(CFG)) if os.path.exists(CFG) else {}
        view = saved.get("view", {"lat": 41.55522176514927, "lon": -87.78959281306139, "zoom": 18})
        self.lat, self.lon, self.zoom = view["lat"], view["lon"], int(view["zoom"])

        self.layers = []
        for name, fn in (("floorplan", "floorplan.png"), ("booth_map", "booth_map.png")):
            img = Image.open(os.path.join(DRAWABLE, fn)).convert("RGBA")
            p = saved.get("layers", {}).get(name, {})
            self.layers.append({
                "name": name, "img": img,
                "lat": p.get("lat", self.lat), "lon": p.get("lon", self.lon),
                "rot": p.get("rot_deg", 0.0), "mpx": p.get("metres_per_px", 0.05),
                "alpha": tk.DoubleVar(value=p.get("alpha", 0.6)),
                "on": tk.BooleanVar(value=True),
            })

        self.active = tk.IntVar(value=0)
        self.tiles = Tiles(lambda: root.after(0, self.draw))
        self._build_ui()
        self.draw()

    # ---------- ui ----------
    def _build_ui(self):
        bar = ttk.Frame(self.root, padding=6)
        bar.pack(fill="x")
        self.center = tk.StringVar(value=f"{self.lat:.6f}, {self.lon:.6f}")
        ttk.Label(bar, text="view lat,lon:").pack(side="left")
        ttk.Entry(bar, textvariable=self.center, width=24).pack(side="left", padx=4)
        ttk.Button(bar, text="Go", command=self.goto).pack(side="left")
        self.zlbl = ttk.Label(bar, text="")
        self.zlbl.pack(side="left", padx=10)
        ttk.Button(bar, text="Save + print (s)", command=self.dump).pack(side="right")

        for i, L in enumerate(self.layers):
            f = ttk.Frame(self.root, padding=(6, 0))
            f.pack(fill="x")
            ttk.Radiobutton(f, text=L["name"], value=i, variable=self.active, width=12).pack(side="left")
            ttk.Checkbutton(f, text="show", variable=L["on"], command=self.draw).pack(side="left")
            ttk.Scale(f, from_=0, to=1, variable=L["alpha"], command=lambda _e: self.draw(),
                      length=140).pack(side="left", padx=6)
            L["lbl"] = ttk.Label(f, text="", font=("Consolas", 9))
            L["lbl"].pack(side="left", padx=8)

        self.canvas = tk.Canvas(self.root, width=W, height=H, bg="#ddd", highlightthickness=0)
        self.canvas.pack()
        c = self.canvas
        c.bind("<ButtonPress-1>", self.press)
        c.bind("<B1-Motion>", self.drag_layer)
        c.bind("<ButtonPress-3>", self.press)
        c.bind("<B3-Motion>", self.drag_view)
        c.bind("<MouseWheel>", self.wheel)
        self.root.bind("<Key>", self.key)
        c.focus_set()

    # ---------- geometry ----------
    def origin(self):
        cx, cy = latlon_to_world(self.lat, self.lon, self.zoom)
        return cx - W / 2, cy - H / 2

    def layer_affine(self, L):
        """Inverse affine (PIL AFFINE coeffs): screen px -> image px."""
        s = L["mpx"] / metres_per_worldpx(L["lat"], self.zoom)  # world px per image px
        th = math.radians(L["rot"])
        cos, sin = math.cos(th), math.sin(th)
        ox, oy = self.origin()
        ax, ay = latlon_to_world(L["lat"], L["lon"], self.zoom)
        asx, asy = ax - ox, ay - oy  # anchor on screen
        cx, cy = L["img"].width / 2, L["img"].height / 2
        a, b = cos / s, sin / s
        d, e = -sin / s, cos / s
        return (a, b, cx - a * asx - b * asy, d, e, cy - d * asx - e * asy)

    def screen_to_img_delta(self, L, dx, dy):
        a, b, _, d, e, _ = self.layer_affine(L)
        return a * dx + b * dy, d * dx + e * dy

    # ---------- render ----------
    def draw(self):
        ox, oy = self.origin()
        frame = Image.new("RGB", (W, H), "#e8e4dc")
        n = 2 ** self.zoom
        for tx in range(int(ox // TILE), int((ox + W) // TILE) + 1):
            for ty in range(int(oy // TILE), int((oy + H) // TILE) + 1):
                if not (0 <= ty < n):
                    continue
                t = self.tiles.get(self.zoom, tx % n, ty)
                if t:
                    frame.paste(t, (int(tx * TILE - ox), int(ty * TILE - oy)))
        frame = frame.convert("RGBA")

        for L in self.layers:
            if not L["on"].get():
                continue
            warped = L["img"].transform((W, H), Image.AFFINE, self.layer_affine(L), resample=Image.BILINEAR)
            alpha = warped.getchannel("A").point(lambda v: int(v * L["alpha"].get()))
            warped.putalpha(alpha)
            frame = Image.alpha_composite(frame, warped)
            L["lbl"].config(text=f"{L['lat']:.6f},{L['lon']:.6f}  rot {L['rot']:+.2f}°  {L['mpx']:.4f} m/px")

        self.photo = ImageTk.PhotoImage(frame)
        self.canvas.delete("all")
        self.canvas.create_image(0, 0, anchor="nw", image=self.photo)
        self.zlbl.config(text=f"z{self.zoom}  {metres_per_worldpx(self.lat, self.zoom):.3f} m/screen-px")

    # ---------- events ----------
    def press(self, e):
        self.px, self.py = e.x, e.y

    def drag_layer(self, e):
        L = self.layers[self.active.get()]
        ox, oy = self.origin()
        ax, ay = latlon_to_world(L["lat"], L["lon"], self.zoom)
        L["lat"], L["lon"] = world_to_latlon(ax + e.x - self.px, ay + e.y - self.py, self.zoom)
        self.px, self.py = e.x, e.y
        self.draw()

    def drag_view(self, e):
        cx, cy = latlon_to_world(self.lat, self.lon, self.zoom)
        self.lat, self.lon = world_to_latlon(cx - (e.x - self.px), cy - (e.y - self.py), self.zoom)
        self.px, self.py = e.x, e.y
        self.center.set(f"{self.lat:.6f}, {self.lon:.6f}")
        self.draw()

    def wheel(self, e):
        step = 1 if e.delta > 0 else -1
        L = self.layers[self.active.get()]
        if e.state & 0x0001:  # shift
            self.nudge(rot=step * 0.5)
        elif e.state & 0x0004:  # ctrl
            self.nudge(scale=1.005 ** step)
        else:
            self.zoom = max(1, min(21, self.zoom + step))
            self.draw()
        _ = L

    def key(self, e):
        k = e.keysym
        pan = {"Left": (-40, 0), "Right": (40, 0), "Up": (0, -40), "Down": (0, 40)}
        if k in pan:
            cx, cy = latlon_to_world(self.lat, self.lon, self.zoom)
            self.lat, self.lon = world_to_latlon(cx + pan[k][0], cy + pan[k][1], self.zoom)
            self.center.set(f"{self.lat:.6f}, {self.lon:.6f}")
            self.draw()
        elif k in ("bracketleft", "bracketright"):
            self.nudge(rot=-0.5 if k == "bracketleft" else 0.5)
        elif k in ("minus", "equal"):
            self.nudge(scale=0.995 if k == "minus" else 1.005)
        elif k == "s":
            self.dump()

    def nudge(self, rot=0.0, scale=1.0):
        L = self.layers[self.active.get()]
        L["rot"] = (L["rot"] + rot) % 360
        L["mpx"] *= scale
        self.draw()

    def goto(self):
        try:
            lat, lon = (float(v) for v in self.center.get().replace(",", " ").split())
        except ValueError:
            return
        self.lat, self.lon = lat, lon
        self.draw()

    def dump(self):
        out = {
            "view": {"lat": round(self.lat, 7), "lon": round(self.lon, 7), "zoom": self.zoom},
            "layers": {L["name"]: {
                "image": f"{L['name']}.png",
                "width_px": L["img"].width, "height_px": L["img"].height,
                "lat": round(L["lat"], 7), "lon": round(L["lon"], 7),
                "rot_deg": round(L["rot"], 3),
                "metres_per_px": round(L["mpx"], 6),
                "alpha": round(L["alpha"].get(), 2),
            } for L in self.layers},
        }
        txt = json.dumps(out, indent=2)
        with open(CFG, "w") as f:
            f.write(txt + "\n")
        self.root.clipboard_clear()
        self.root.clipboard_append(txt)
        print(f"\n--- saved {CFG} (also on clipboard) ---\n{txt}")


def demo():
    """Round-trip the transform: image centre and a known offset land where they should."""
    z = 18
    L = {"lat": 39.7392, "lon": -104.9903, "rot": 90.0, "mpx": 0.05,
         "img": Image.new("RGBA", (1000, 800))}
    app = App.__new__(App)
    app.lat, app.lon, app.zoom = L["lat"], L["lon"], z
    a, b, c, d, e, f = App.layer_affine(app, L)
    ox, oy = App.origin(app)
    # anchor screen pos maps back to the image centre
    ax, ay = latlon_to_world(L["lat"], L["lon"], z)
    sx, sy = ax - ox, ay - oy
    assert abs(a * sx + b * sy + c - 500) < 1e-6 and abs(d * sx + e * sy + f - 400) < 1e-6
    # rot=90 => image "up" points east: a pixel 100px above centre sits east of the anchor
    k = 1 / metres_per_worldpx(L["lat"], z)
    px, py = sx + 100 * L["mpx"] * k, sy  # 100 image px worth of metres, due east on screen
    ix, iy = a * px + b * py + c, d * px + e * py + f
    assert abs(ix - 500) < 1e-6 and abs(iy - 300) < 1e-6, (ix, iy)
    print("demo ok")


if __name__ == "__main__":
    import sys
    if "--demo" in sys.argv:
        demo()
    else:
        root = tk.Tk()
        App(root)
        root.mainloop()
