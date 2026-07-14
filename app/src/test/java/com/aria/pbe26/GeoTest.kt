package com.aria.pbe26

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * The overlays and every booth pin are placed by the maths in Geo.kt. If it drifts, tables end up
 * on the wrong side of the hall and nobody notices until the show floor.
 */
class GeoTest {

    private val z = 20f

    /** Metres between two world-pixel points, at the venue's latitude. */
    private fun metres(a: Pair<Double, Double>, b: Pair<Double, Double>, zoom: Float = z) =
        hypot(a.first - b.first, a.second - b.second) * mPerWorldPx(BOOTHS.lat, zoom)

    /** Compass bearing from world-pixel point [a] to [b]: y grows south, so north is -y. */
    private fun bearing(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val deg = Math.toDegrees(atan2(b.first - a.first, a.second - b.second))
        return (deg + 360.0) % 360.0
    }

    @Test
    fun `mercator round trips`() {
        val lat = 41.5554521
        val lon = -87.7892602
        assertEquals(lat, latAt(worldY(lat, z), z), 1e-9)
        assertEquals(lon, lonAt(worldX(lon, z), z), 1e-9)
    }

    @Test
    fun `ground scale is about 11 cm per pixel at zoom 20`() {
        assertEquals(0.111, mPerWorldPx(BOOTHS.lat, z), 0.005)
    }

    @Test
    fun `the centre of an image sits on its anchor`() {
        val centre = BOOTHS.world(0.5f, 0.5f, z)
        assertEquals(worldX(BOOTHS.lon, z), centre.first, 1e-6)
        assertEquals(worldY(BOOTHS.lat, z), centre.second, 1e-6)
    }

    @Test
    fun `the image is laid out at its stated ground scale`() {
        // Left edge to right edge is the image's width in pixels, times metres per pixel.
        val left = BOOTHS.world(0f, 0.5f, z)
        val right = BOOTHS.world(1f, 0.5f, z)
        assertEquals(BOOTHS.w * BOOTHS.mPerPx, metres(left, right), 0.01)
    }

    @Test
    fun `the top of the image points at its stated bearing`() {
        val centre = BOOTHS.world(0.5f, 0.5f, z)
        val up = BOOTHS.world(0.5f, 0f, z) // straight up, in image space
        assertEquals(BOOTHS.rotDeg, bearing(centre, up), 0.001)
    }

    @Test
    fun `zoom changes pixels, not the ground truth`() {
        // The same two corners must be the same distance apart on the earth at any zoom.
        val far = metres(BOOTHS.world(0f, 0f, 22f), BOOTHS.world(1f, 1f, 22f), 22f)
        val near = metres(BOOTHS.world(0f, 0f, 17f), BOOTHS.world(1f, 1f, 17f), 17f)
        assertTrue("$near vs $far", abs(near - far) < 0.05)
    }

    @Test
    fun `the two overlays cover the same patch of earth`() {
        // A mis-pasted alignment usually lands one of them in another county. The booth map is a
        // detail of the floorplan, so their centres must be close.
        val gap = metres(
            BOOTHS.world(0.5f, 0.5f, z),
            FLOORPLAN.world(0.5f, 0.5f, z),
        )
        assertTrue("overlay centres are ${gap.toInt()} m apart", gap < 150.0)
    }
}
