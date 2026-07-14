# PBE26

An offline pocket guide to the **Polish & Beauty Expo 2026** (Tinley Park Convention Center,
July 18–19 2026). Every vendor, every polish they are bringing, and a live map of the show floor
that knows which table you are standing next to.

## What it does

- **Info** — dates, venue, schedule. Every slot doubles as a journal entry that saves as you type.
- **Vendors** — 48 vendors, searchable. Blurb, links, a "what to buy" note, and every polish they
  are bringing; tap one for a full-screen viewer you can swipe and pinch. Heart the ones you want.
- **Map** — dark OpenStreetMap tiles with the floorplan and booth map drawn on top as georeferenced
  overlays, so they stay locked to the earth as you pan, zoom and rotate. Booth pins go through the
  same transform, so they cannot drift off their tables. Compass, GPS locate, tap a pin for the
  vendor. The pin button on any vendor flies here and highlights their table.
- **Saved** — scratch notes, journal entries, favourited polishes, starred vendors.

## Build

JDK 17 and the Android SDK (`local.properties` points at it). The wrapper fetches its own Gradle.

```bash
./gradlew installDebug      # build + push to an attached device
./gradlew test              # unit tests
./gradlew assembleRelease   # minified; signed with the debug key, sideload only
```

## How the map is aligned

`python tools/align_maps.py` (needs `pillow`) shows OSM tiles with both overlays on top: drag to
move, shift+wheel to rotate, ctrl+wheel to scale, `s` to save. It writes `tools/map_align.json` —
each image's centre lat/lon, the bearing of its "up" edge, and its ground scale in metres per pixel.

Paste those into `FLOORPLAN` and `BOOTHS` in `app/src/main/java/com/aria/pbe26/Geo.kt`. `CatalogueTest`
fails if the two disagree, so forgetting to paste is caught by the tests, not by a wrong pin at the
venue. When editing an overlay PNG, keep its pixel dimensions: booth positions are fractions of the
image, so cropping or padding moves every pin.


## Credits

Map tiles © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors, ©
[CARTO](https://carto.com/attributions). Vendor information and swatch photos are the vendors' own,
gathered from the expo's public vendor list and the community swatch Airtable.
