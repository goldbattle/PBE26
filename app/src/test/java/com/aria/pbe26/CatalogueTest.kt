package com.aria.pbe26

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.hypot

/**
 * The catalogue in assets/vendors.json and the alignment in Geo.kt are both hand-fed data. These
 * check the app's two silent failure modes: a vendor the app cannot parse, and a booth pin that
 * lands somewhere that is not the venue.
 */
class CatalogueTest {

    // Unit tests run with the app module as the working directory.
    private val vendors = parseVendors(File("src/main/assets/vendors.json").readText())
    private val booths = vendors.mapNotNull { v -> v.booth?.let { v to it } }

    @Test
    fun `the catalogue parses`() {
        assertTrue("only ${vendors.size} vendors parsed", vendors.size > 40)
        assertTrue("vendors need names", vendors.all { it.name.isNotBlank() })
        assertEquals("duplicate vendor names", vendors.size, vendors.map { it.name }.distinct().size)
    }

    @Test
    fun `swatches point at a real asset`() {
        val files = vendors.flatMap { it.swatches }.map { it.file }
        assertTrue("some vendor should have swatches", files.isNotEmpty())
        val missing = files.filterNot { File("src/main/assets/$it").exists() }
        assertTrue("swatch files not in assets: ${missing.take(5)}", missing.isEmpty())
    }

    @Test
    fun `booths are on the booth map, at their own table`() {
        assertTrue("no booths parsed", booths.size > 20)
        booths.forEach { (v, b) ->
            assertTrue("${v.name}: table ${b.table}", b.table > 0)
            assertTrue("${v.name}: x ${b.x} off the image", b.x in 0f..1f)
            assertTrue("${v.name}: y ${b.y} off the image", b.y in 0f..1f)
        }
        val tables = booths.map { (_, b) -> b.table }
        assertEquals("two vendors on one table", tables.size, tables.distinct().size)
    }

    @Test
    fun `every booth lands inside the venue`() {
        val z = 20f
        val venue = FLOORPLAN.world(0.5f, 0.5f, z)
        booths.forEach { (v, b) ->
            val p = BOOTHS.world(b.x, b.y, z)
            val m = hypot(p.first - venue.first, p.second - venue.second) * mPerWorldPx(FLOORPLAN.lat, z)
            assertTrue("${v.name} (table ${b.table}) is ${m.toInt()} m from the venue", m < 250.0)
        }
    }

    /**
     * Geo.kt hardcodes what tools/align_maps.py produced. Re-aligning the maps and forgetting to
     * paste the numbers back is the easy mistake; this is what catches it.
     */
    @Test
    fun `the alignment constants match the alignment tool's output`() {
        val json = JSONObject(File("../tools/map_align.json").readText()).getJSONObject("layers")
        listOf("floorplan" to FLOORPLAN, "booth_map" to BOOTHS).forEach { (key, geo) ->
            val o = json.getJSONObject(key)
            assertEquals("$key lat", o.getDouble("lat"), geo.lat, 1e-7)
            assertEquals("$key lon", o.getDouble("lon"), geo.lon, 1e-7)
            assertEquals("$key rotation", o.getDouble("rot_deg"), geo.rotDeg, 1e-3)
            assertEquals("$key scale", o.getDouble("metres_per_px"), geo.mPerPx, 1e-6)
            assertEquals("$key width", o.getInt("width_px"), geo.w)
            assertEquals("$key height", o.getInt("height_px"), geo.h)
        }
    }
}
