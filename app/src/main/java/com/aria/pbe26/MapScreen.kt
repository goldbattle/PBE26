package com.aria.pbe26

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

// The map itself: dark OSM tiles, the two aligned overlays on top, booth pins on top of those.
// The georeferencing maths behind it all lives in Geo.kt.

private const val TILE_URL = "https://basemaps.cartocdn.com/dark_all"
private const val MAX_TILE_Z = 20 // CARTO's deepest tile; past this the map keeps zooming, upscaled
private const val MIN_Z = 16f
private const val MAX_Z = 24f

// Pins are finger targets, not decorations: a fat dot, and a generous radius to tap it by.
private const val DOT = 11f
private const val MARKER = 30f

/** The focused pin, in a colour no other pin uses, so it reads at a glance among the pink dots. */
private val Focused = Color(0xFFE53935)
private const val TAP_SLOP = 44.0

/**
 * The viewport. Lives outside the composition on purpose: hopping to another tab and back must not
 * throw away where the user was. A fresh process starts it over, framed on the booth map.
 */
private object Cam {
    var lat by mutableStateOf(BOOTHS.lat)
    var lon by mutableStateOf(BOOTHS.lon)
    var zoom by mutableStateOf(20.5f)

    /** Compass bearing shown at the top of the screen; the booth map's own bearing draws it upright. */
    var bearing by mutableStateOf(BOOTHS.rotDeg.toFloat())

    var floor by mutableStateOf(false) // the floorplan is context, not the thing you came here for
    var booths by mutableStateOf(true)
    var pins by mutableStateOf(true)
}

// ---------- tiles ----------

/** Once per process is enough; [TileStore] is rebuilt every time the Map tab comes back. */
private var prefetched = false

/**
 * Dark OSM tiles, memory + disk cached. Missing tiles just leave the background showing.
 *
 * ponytail: cacheDir, so Android may evict the venue tiles under storage pressure — in practice it
 * holds for days and [prefetch] refills it in a minute. Move to filesDir if that ever bites.
 */
private class TileStore(private val ctx: Context, private val scope: CoroutineScope) {
    private val loaded = mutableStateMapOf<String, ImageBitmap>()
    private val loading = mutableSetOf<String>()
    private val dir = File(ctx.cacheDir, "tiles").apply { mkdirs() }

    /** Bytes for one tile, from disk if we have it, otherwise from the network (and then to disk). */
    private fun bytes(z: Int, x: Int, y: Int): ByteArray? = runCatching {
        val f = File(dir, "${z}_${x}_$y.png")
        if (f.exists()) return@runCatching f.readBytes()
        val c = URL("$TILE_URL/$z/$x/$y.png").openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "PBE26/1.0")
        c.inputStream.use { it.readBytes() }.also { f.writeBytes(it) }
    }.getOrNull()

    fun get(z: Int, x: Int, y: Int): ImageBitmap? {
        val key = "${z}_${x}_$y"
        loaded[key]?.let { return it }
        if (loading.add(key)) {
            scope.launch(Dispatchers.IO) {
                val bmp = bytes(z, x, y)?.let {
                    BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap()
                }
                withContext(Dispatchers.Main) {
                    if (bmp != null) loaded[key] = bmp else loading.remove(key)
                }
            }
        }
        return null
    }

    /** Pull every tile covering the venue, once, so the map works on the show floor without data. */
    fun prefetch(lat: Double, lon: Double, metres: Double, zooms: IntRange) {
        if (prefetched) return
        prefetched = true
        scope.launch(Dispatchers.IO) {
            for (z in zooms) {
                val r = metres / mPerWorldPx(lat, z.toFloat()) // half-span in world pixels
                val x0 = ((worldX(lon, z.toFloat()) - r) / TILE).toInt()
                val x1 = ((worldX(lon, z.toFloat()) + r) / TILE).toInt()
                val y0 = ((worldY(lat, z.toFloat()) - r) / TILE).toInt()
                val y1 = ((worldY(lat, z.toFloat()) + r) / TILE).toInt()
                for (x in x0..x1) for (y in y0..y1) bytes(z, x, y)
            }
        }
    }
}

/** Overlay PNGs are big; decode once and keep them for the life of the process. */
@Composable
private fun rememberMap(geo: Geo): ImageBitmap? {
    val ctx = LocalContext.current
    return remember(geo.asset) {
        runCatching {
            ctx.assets.open(geo.asset).use { BitmapFactory.decodeStream(it) }!!.asImageBitmap()
        }.getOrNull()
    }
}

// ---------- location ----------

private fun hasLocation(ctx: Context) =
    ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

@Composable
private fun rememberLocation(enabled: Boolean): Location? {
    val ctx = LocalContext.current
    var loc by remember { mutableStateOf<Location?>(null) }
    DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val lm = ctx.getSystemService(LocationManager::class.java)
        val listener = LocationListener { loc = it }
        // No Play Services dependency: ask the platform for both providers and take whatever fixes
        // arrive. Indoors that is usually the network provider.
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { p ->
            runCatching { lm.requestLocationUpdates(p, 1000L, 1f, listener) }
        }
        onDispose { lm.removeUpdates(listener) }
    }
    return loc
}

// ---------- screen ----------

@Composable
fun MapScreen(vendors: List<Vendor>, focus: Vendor?, onOpenVendor: (Vendor) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tiles = remember { TileStore(ctx, scope) }
    val floorImg = rememberMap(FLOORPLAN)
    val boothImg = rememberMap(BOOTHS)
    val pins = remember(vendors) { vendors.mapNotNull { v -> v.booth?.let { v to it } } }

    // A 500 m box around the venue at every zoom the map allows: ~450 tiles, a few MB, fetched once.
    // Downloaded sequentially on one IO coroutine so it stays a trickle, not a stampede.
    LaunchedEffect(Unit) { tiles.prefetch(FLOORPLAN.lat, FLOORPLAN.lon, 250.0, 16..MAX_TILE_Z) }

    var menu by remember { mutableStateOf(false) }
    var granted by remember { mutableStateOf(hasLocation(ctx)) }
    val ask = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok -> granted = ok }
    val me = rememberLocation(granted)

    // Arriving from a vendor's navigate button: show the booth map and fly to their table.
    LaunchedEffect(focus) {
        val b = focus?.booth ?: return@LaunchedEffect
        Cam.booths = true
        Cam.pins = true
        Cam.zoom = 21.5f
        val (wx, wy) = BOOTHS.world(b.x, b.y, Cam.zoom)
        Cam.lat = latAt(wy, Cam.zoom)
        Cam.lon = lonAt(wx, Cam.zoom)
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF0B0B0C))) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTransformGestures { _, pan, gzoom, rot ->
                    Cam.bearing -= rot
                    val z = (Cam.zoom + ln(gzoom.toDouble()).toFloat() / ln(2f)).coerceIn(MIN_Z, MAX_Z)
                    // Pan arrives in screen pixels; undo the map rotation to get world pixels.
                    val b = Math.toRadians(Cam.bearing.toDouble())
                    val dx = pan.x * cos(b) - pan.y * sin(b)
                    val dy = pan.x * sin(b) + pan.y * cos(b)
                    val cx = worldX(Cam.lon, Cam.zoom) - dx
                    val cy = worldY(Cam.lat, Cam.zoom) - dy
                    Cam.lat = latAt(cy, Cam.zoom)
                    Cam.lon = lonAt(cx, Cam.zoom)
                    Cam.zoom = z
                }
            }.pointerInput(pins) {
                detectTapGestures { tap ->
                    if (!Cam.pins) return@detectTapGestures
                    val (wx, wy) = screenToWorld(tap, size)
                    // Nearest pin wins, if the tap landed near enough to mean it.
                    val hit = pins.minByOrNull { (_, b) ->
                        val (px, py) = BOOTHS.world(b.x, b.y, Cam.zoom)
                        hypot(px - wx, py - wy)
                    }?.takeIf { (_, b) ->
                        val (px, py) = BOOTHS.world(b.x, b.y, Cam.zoom)
                        hypot(px - wx, py - wy) < TAP_SLOP
                    }
                    hit?.let { (v, _) -> onOpenVendor(v) }
                }
            },
        ) {
            val zoom = Cam.zoom
            val centre = Offset(size.width / 2, size.height / 2)
            val wcx = worldX(Cam.lon, zoom)
            val wcy = worldY(Cam.lat, zoom)
            // North-up screen coordinates; the whole scene is rotated once, at the end.
            fun at(wx: Double, wy: Double) =
                Offset((wx - wcx).toFloat() + centre.x, (wy - wcy).toFloat() + centre.y)

            rotate(-Cam.bearing, centre) {
                drawTiles(tiles, wcx, wcy, zoom, centre)
                if (Cam.floor && floorImg != null) drawGeo(floorImg, FLOORPLAN, zoom, ::at, 0.55f)
                if (Cam.booths && boothImg != null) drawGeo(boothImg, BOOTHS, zoom, ::at, 0.95f)

                if (Cam.pins) {
                    // The focused table last, so its marker sits on top of its neighbours.
                    pins.sortedBy { (v, _) -> v.name == focus?.name }.forEach { (v, b) ->
                        val (wx, wy) = BOOTHS.world(b.x, b.y, zoom)
                        val p = at(wx, wy)
                        if (v.name == focus?.name) {
                            rotate(Cam.bearing, p) { drawMarker(p) } // upright however the map is turned
                        } else {
                            drawCircle(Color.Black.copy(alpha = 0.55f), DOT + 3f, p)
                            drawCircle(Pink, DOT, p)
                        }
                    }
                }

                me?.let { l ->
                    val p = at(worldX(l.longitude, zoom), worldY(l.latitude, zoom))
                    val r = (l.accuracy / mPerWorldPx(l.latitude, zoom)).toFloat()
                    drawCircle(Color(0xFF4FC3F7).copy(alpha = 0.18f), r.coerceAtLeast(8f), p)
                    drawCircle(Color.White, 8f, p)
                    drawCircle(Color(0xFF2196F3), 6f, p)
                }
            }
        }

        // Focused vendor, since this page has no title bar.
        focus?.booth?.let { b ->
            Surface(
                Modifier.align(Alignment.TopStart).padding(12.dp),
                shape = RoundedCornerShape(20.dp),
                color = Color.Black.copy(alpha = 0.75f),
            ) {
                Text(
                    "${focus.name} · Table ${b.table}",
                    Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    color = Pink,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                )
            }
        }

        Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            MapButton(Icons.Default.Layers, "Layers") { menu = true }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                LayerItem("Venue floorplan", Cam.floor) { Cam.floor = it }
                LayerItem("Booth map", Cam.booths) { Cam.booths = it }
                LayerItem("Booth pins", Cam.pins) { Cam.pins = it }
            }
        }

        Column(
            Modifier.align(Alignment.BottomEnd).padding(12.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Points at north; tap to square the booth map back up.
            MapButton(
                Icons.Default.Navigation,
                "North is this way — tap to straighten the map",
                Modifier.graphicsLayer { rotationZ = -Cam.bearing },
            ) { Cam.bearing = BOOTHS.rotDeg.toFloat() }
            MapButton(Icons.Default.MyLocation, "Centre on me") {
                if (!granted) ask.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                me?.let {
                    Cam.lat = it.latitude
                    Cam.lon = it.longitude
                    Cam.zoom = Cam.zoom.coerceAtLeast(20f)
                }
            }
            Column {
                MapButton(Icons.Default.Add, "Zoom in") { Cam.zoom = (Cam.zoom + 1f).coerceAtMost(MAX_Z) }
                Spacer(Modifier.height(4.dp))
                MapButton(Icons.Default.Remove, "Zoom out") { Cam.zoom = (Cam.zoom - 1f).coerceAtLeast(MIN_Z) }
            }
        }

        Text(
            "© OpenStreetMap · CARTO",
            Modifier.align(Alignment.BottomStart).padding(10.dp),
            color = Color.White.copy(alpha = 0.4f),
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun MapButton(
    icon: ImageVector,
    desc: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color(0xFF1C1C1E).copy(alpha = 0.92f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, modifier.size(22.dp), tint = Color.White)
    }
}

@Composable
private fun LayerItem(label: String, on: Boolean, set: (Boolean) -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = { set(!on) },
        leadingIcon = { Checkbox(checked = on, onCheckedChange = { set(it) }) },
    )
}

// ---------- drawing ----------

private fun DrawScope.drawTiles(
    tiles: TileStore,
    wcx: Double,
    wcy: Double,
    zoom: Float,
    centre: Offset,
) {
    val tz = zoom.roundToInt().coerceIn(1, MAX_TILE_Z)
    val f = 2.0.pow(zoom - tz.toDouble()) // a tz tile is this many world pixels per tile pixel
    val span = TILE * f
    val reach = hypot(size.width, size.height) / 2.0 // rotation-proof: cover the circumscribed square
    val n = 1 shl tz
    val x0 = ((wcx - reach) / span).toInt() - 1
    val x1 = ((wcx + reach) / span).toInt() + 1
    val y0 = ((wcy - reach) / span).toInt() - 1
    val y1 = ((wcy + reach) / span).toInt() + 1
    val dst = IntSize(ceil(span).toInt() + 1, ceil(span).toInt() + 1) // +1 hides seams from rounding
    for (tx in x0..x1) {
        for (ty in y0..y1) {
            if (ty !in 0 until n) continue
            val img = tiles.get(tz, ((tx % n) + n) % n, ty) ?: continue
            drawImage(
                image = img,
                dstOffset = IntOffset(
                    (tx * span - wcx + centre.x).roundToInt(),
                    (ty * span - wcy + centre.y).roundToInt(),
                ),
                dstSize = dst,
            )
        }
    }
}

/** Undo the camera: a touch point back to world pixels, so a tap can be matched against a booth. */
private fun screenToWorld(tap: Offset, view: IntSize): Pair<Double, Double> {
    val b = Math.toRadians(Cam.bearing.toDouble())
    val dx = tap.x - view.width / 2.0
    val dy = tap.y - view.height / 2.0
    return worldX(Cam.lon, Cam.zoom) + (dx * cos(b) - dy * sin(b)) to
        worldY(Cam.lat, Cam.zoom) + (dx * sin(b) + dy * cos(b))
}

/** The classic teardrop, tip on the table it marks. */
private fun DrawScope.drawMarker(tip: Offset) {
    val head = Offset(tip.x, tip.y - MARKER * 1.7f)
    val neck = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(head.x - MARKER * 0.72f, head.y + MARKER * 0.72f)
        lineTo(head.x + MARKER * 0.72f, head.y + MARKER * 0.72f)
        close()
    }
    // Black under white: the map is busy and pale in places, and a white rim alone disappears on it.
    drawCircle(Color.Black, MARKER + 6f, head)
    drawPath(neck, Color.Black, style = Stroke(width = 12f))
    drawCircle(Color.White, MARKER + 3f, head)
    drawPath(neck, Color.White, style = Stroke(width = 6f))
    drawPath(neck, Focused)
    drawCircle(Focused, MARKER, head)
    drawCircle(Color.Black.copy(alpha = 0.55f), MARKER * 0.34f, head) // the hole
}

/** Paste an aligned image onto the map: centred on its anchor, turned to its bearing, ground-scaled. */
private fun DrawScope.drawGeo(
    img: ImageBitmap,
    geo: Geo,
    zoom: Float,
    at: (Double, Double) -> Offset,
    alpha: Float,
) {
    val anchor = at(worldX(geo.lon, zoom), worldY(geo.lat, zoom))
    val s = geo.pxScale(zoom).toFloat()
    withTransform({
        rotate(geo.rotDeg.toFloat(), anchor)
        scale(s, s, anchor)
    }) {
        drawImage(
            img,
            topLeft = Offset(anchor.x - geo.w / 2f, anchor.y - geo.h / 2f),
            alpha = alpha,
        )
    }
}
