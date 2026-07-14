package com.aria.pbe26

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh

/**
 * Georeferencing: how the venue's two drawings sit on the earth, and the Web Mercator maths that
 * puts them — and every booth on them — in the same coordinate system as the map tiles.
 *
 * Deliberately free of Compose and Android so it can be unit-tested on the JVM. See GeoTest.
 */

/**
 * How an image sits on the earth: [lat]/[lon] is the image centre, [rotDeg] is the compass bearing
 * the image's "up" edge points at, [mPerPx] is its ground scale.
 *
 * The overlays live in assets/maps so they can be edited freely (feathered edges, transparency)
 * without touching resource ids. These numbers come from tools/map_align.json — re-run
 * `python tools/align_maps.py`, drag the layers over the tiles again, and paste the new values here.
 * Keep an image's pixel size the same when you edit it, or the alignment and every pin shift.
 */
internal data class Geo(
    val asset: String,
    val lat: Double,
    val lon: Double,
    val rotDeg: Double,
    val mPerPx: Double,
    val w: Int,
    val h: Int,
)

internal val FLOORPLAN =
    Geo("maps/floorplan.png", 41.5551074, -87.7898503, 89.5, 0.196097, 1800, 1390)
internal val BOOTHS =
    Geo("maps/booth_map.png", 41.5554521, -87.7892602, 119.5, 0.045935, 2048, 1387)

internal const val TILE = 256

// Web Mercator, in world pixels at a (fractional) zoom.

internal fun worldX(lon: Double, z: Float) = (lon + 180.0) / 360.0 * TILE * 2.0.pow(z.toDouble())

internal fun worldY(lat: Double, z: Float): Double {
    val s = sin(Math.toRadians(lat))
    return (0.5 - ln((1 + s) / (1 - s)) / (4 * PI)) * TILE * 2.0.pow(z.toDouble())
}

internal fun lonAt(x: Double, z: Float) = x / (TILE * 2.0.pow(z.toDouble())) * 360.0 - 180.0

internal fun latAt(y: Double, z: Float): Double {
    val n = TILE * 2.0.pow(z.toDouble())
    return Math.toDegrees(atan(sinh(PI * (1 - 2 * y / n))))
}

/** Mercator stretches away from the equator, so ground scale depends on latitude as well as zoom. */
internal fun mPerWorldPx(lat: Double, z: Float) =
    156543.03392804097 * cos(Math.toRadians(lat)) / 2.0.pow(z.toDouble())

/** World pixels per image pixel at this zoom. */
internal fun Geo.pxScale(z: Float) = mPerPx / mPerWorldPx(lat, z)

/**
 * Where a point given as fractions of the image lands, in world pixels.
 *
 * This is the same anchor, rotation and scale the image itself is drawn with, which is why a booth
 * pin can never drift off the table it marks.
 */
internal fun Geo.world(fx: Float, fy: Float, z: Float): Pair<Double, Double> {
    val s = pxScale(z)
    val dx = (fx - 0.5) * w
    val dy = (fy - 0.5) * h
    val th = Math.toRadians(rotDeg)
    return worldX(lon, z) + s * (dx * cos(th) - dy * sin(th)) to
        worldY(lat, z) + s * (dy * cos(th) + dx * sin(th))
}
