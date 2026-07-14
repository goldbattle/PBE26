package com.aria.pbe26

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.LruCache
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import org.json.JSONArray

// ---------- data ----------

data class Vendor(
    val name: String,
    val owner: String,
    val page: String,
    val bio: String,
    val blurb: String, // what the brand wrote in the community swatch Airtable
    val website: String,
    val img: String, // asset path, e.g. "vendors/pinnacle-polish.jpg"
    val socials: List<Pair<String, String>>, // platform -> url
    val swatches: List<Swatch>,
    val booth: Booth?, // null = not on the booth map
)

/** Table number, and where that table sits on booth_map.png (fractions of the image). */
data class Booth(val table: Int, val x: Float, val y: Float)

data class Swatch(val name: String, val file: String)

private fun loadVendors(ctx: Context): List<Vendor> {
    val arr = JSONArray(ctx.assets.open("vendors.json").bufferedReader().use { it.readText() })
    return (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        val s = o.getJSONObject("socials")
        val sw = o.optJSONArray("swatches")
        Vendor(
            name = o.getString("name"),
            owner = o.optString("owner"),
            page = o.optString("page"),
            bio = o.optString("bio"),
            blurb = o.optString("airtableInfo"),
            website = o.optString("website"),
            img = o.optString("img"),
            socials = s.keys().asSequence().map { it to s.getString(it) }.toList(),
            swatches = (0 until (sw?.length() ?: 0)).map { i ->
                val e = sw!!.getJSONObject(i)
                Swatch(e.optString("name"), e.getString("file"))
            },
            booth = if (o.has("booth")) {
                Booth(o.getInt("booth"), o.getDouble("boothX").toFloat(), o.getDouble("boothY").toFloat())
            } else {
                null
            },
        )
    }.sortedBy { it.name.lowercase() }
}

/**
 * Images ship in assets. 400+ swatches will not all fit in memory, so decode them downsampled and
 * keep them in a size-bounded cache.
 */
private val bitmapCache = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}

@Composable
private fun rememberAsset(path: String, sample: Int = 1): ImageBitmap? {
    val ctx = LocalContext.current
    return remember(path, sample) {
        val key = "$path@$sample"
        bitmapCache[key] ?: runCatching {
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            ctx.assets.open(path).use { BitmapFactory.decodeStream(it, null, opts) }!!
                .asImageBitmap()
                .also { bitmapCache.put(key, it) }
        }.getOrNull()
    }
}

/** Bookmarks, vendor notes and journals. Everything autosaves to SharedPreferences. */
class Saved(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("pbe26", Context.MODE_PRIVATE)

    val bookmarks = mutableStateSetOf<String>().apply {
        addAll(prefs.getStringSet("bookmarks", emptySet())!!)
    }
    val notes = mutableStateMapOf<String, String>().apply {
        prefs.all.forEach { (k, v) ->
            if (k.startsWith("note:") && v is String && v.isNotBlank()) put(k.removePrefix("note:"), v)
        }
    }
    val journals = mutableStateMapOf<String, String>().apply {
        prefs.all.forEach { (k, v) ->
            if (k.startsWith("journal:") && v is String) put(k.removePrefix("journal:"), plainText(v))
        }
    }

    fun toggleBookmark(name: String) {
        if (!bookmarks.remove(name)) bookmarks.add(name)
        prefs.edit().putStringSet("bookmarks", bookmarks.toSet()).apply()
    }

    fun setNote(name: String, text: String) {
        if (text.isBlank()) notes.remove(name) else notes[name] = text
        prefs.edit().putString("note:$name", text).apply()
    }

    fun setJournal(key: String, text: String) {
        journals[key] = text
        prefs.edit().putString("journal:$key", text).apply()
    }

    /** Journals used to be a JSON list of blocks; keep the words from any entry written back then. */
    private fun plainText(stored: String): String {
        if (!stored.startsWith("[")) return stored
        return runCatching {
            val arr = JSONArray(stored)
            (0 until arr.length())
                .map { arr.getJSONObject(it).optString("text") }
                .filter { it.isNotBlank() }
                .joinToString("\n\n")
        }.getOrDefault(stored)
    }
}

// ---------- venue content ----------

private data class Link(val label: String, val url: String, val icon: ImageVector)

private const val ADDRESS = "18451 Convention Center Dr, Tinley Park, IL 60477"

private val LINKS = listOf(
    Link("Tickets (Eventbrite)", "https://polishandbeautyexpo2026.eventbrite.com", Icons.Default.ConfirmationNumber),
    Link("Official site", "https://polishandbeautyexpo.com/", Icons.Default.Language),
    Link("Full schedule", "https://polishandbeautyexpo.com/schedule/", Icons.Default.Schedule),
    Link("Vendor list", "https://polishandbeautyexpo.com/more-about-vendors/", Icons.Default.Storefront),
    Link("Instagram @polishandbeautyexpo", "https://www.instagram.com/polishandbeautyexpo", Icons.Default.PhotoCamera),
    Link("Facebook group", "https://www.facebook.com/share/g/cmwJWG9PHfAMGb5p/", Icons.Default.Group),
    Link("Venue: Tinley Park Convention Center", "https://www.tinleyparkconventioncenter.net/", Icons.Default.Business),
    Link("Holiday Inn (attached hotel)", "https://www.ihg.com/holidayinn/hotels/us/en/tinley-park/chitp/hoteldetail", Icons.Default.Hotel),
)

private data class Slot(val time: String, val what: String, val where: String)

private val DAY1 = listOf(
    Slot("10:00a – 12:00p", "Glitter Unique polish-making workshop", "Convention Center"),
    Slot("1:00p – 3:00p", "Glitter Unique polish-making workshop (repeat)", "Convention Center"),
    Slot("6:00p – 8:00p", "Meet & Greet cocktail reception", "South Pavilion & Promenade"),
)
private val DAY2 = listOf(
    Slot("11:00a", "VIP check-in opens", "Exhibit South"),
    Slot("12:00p – 1:00p", "VIP entry (early shopping)", "Exhibit South"),
    Slot("1:00p – 6:00p", "General & Basic admission", "Exhibit South"),
)

// ---------- app ----------

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val vendors = loadVendors(this)
        val saved = Saved(this)
        setContent {
            MaterialTheme(colorScheme = PbeDark) { App(vendors, saved) }
        }
    }
}

private val Pink = Color(0xFFF48FB1)
private val PbeDark = darkColorScheme(
    primary = Pink,
    onPrimary = Color.Black,
    secondary = Color(0xFFCE93D8),
    background = Color.Black,
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF121212),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF1C1C1E),
    onSurfaceVariant = Color(0xFFB3B3B3),
)

private enum class Tab(val label: String, val icon: ImageVector) {
    Info("Info", Icons.Default.Info),
    Vendors("Vendors", Icons.Default.Storefront),
    Map("Map", Icons.Default.Map),
    Saved("Saved", Icons.Default.Star),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vendors: List<Vendor>, saved: Saved) {
    var tab by rememberSaveable { mutableStateOf(Tab.Info) }
    var journal by rememberSaveable { mutableStateOf<String?>(null) } // open journal entry, null = none
    var openVendor by rememberSaveable { mutableStateOf<String?>(null) } // vendor page, null = none
    var mapFocus by rememberSaveable { mutableStateOf<String?>(null) } // vendor to pin on the map
    val vendor = vendors.firstOrNull { it.name == openVendor }

    // Hoisted so coming back from a vendor page lands where you left the list.
    val vendorList = rememberLazyListState()
    var query by rememberSaveable { mutableStateOf("") }

    fun showOnMap(v: Vendor) {
        openVendor = null
        journal = null
        mapFocus = v.name
        tab = Tab.Map
    }

    // Back unwinds the app instead of leaving it: page -> tab -> Info -> (system handles exit).
    BackHandler(enabled = journal != null || openVendor != null || tab != Tab.Info) {
        when {
            journal != null -> journal = null
            openVendor != null -> openVendor = null
            else -> tab = Tab.Info
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        journal ?: vendor?.name ?: "Polish & Beauty Expo 2026",
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    if (journal != null || vendor != null) {
                        IconButton(onClick = { journal = null; openVendor = null }) {
                            Icon(Icons.Default.ArrowBack, "Back")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF101010)) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t && journal == null && openVendor == null,
                        onClick = { tab = t; journal = null; openVendor = null },
                        icon = { Icon(t.icon, null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            val open = journal
            when {
                open != null -> JournalScreen(open, saved)
                vendor != null -> VendorScreen(vendor, saved, ::showOnMap)
                else -> when (tab) {
                    Tab.Info -> InfoScreen(saved) { journal = it }
                    Tab.Vendors -> VendorsScreen(
                        vendors, saved, vendorList, query, { query = it },
                        { openVendor = it.name }, ::showOnMap,
                    )
                    Tab.Map -> MapScreen(vendors.firstOrNull { it.name == mapFocus }) { mapFocus = null }
                    Tab.Saved -> SavedScreen(
                        vendors, saved, { journal = it }, { openVendor = it.name }, ::showOnMap,
                    )
                }
            }
        }
    }
}

// ---------- info ----------

@Composable
private fun InfoScreen(saved: Saved, openJournal: (String) -> Unit) {
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Sat July 18 – Sun July 19, 2026", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { open(ctx, "geo:0,0?q=" + Uri.encode(ADDRESS)) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Place, null, tint = Pink)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Tinley Park Convention Center", color = Pink)
                        Text(
                            "18451 Convention Center Dr\nTinley Park, IL 60477",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text("Tap for directions", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    Icon(Icons.Default.OpenInNew, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Section("Schedule — Saturday, July 18")
        DAY1.forEach { SlotRow(it, saved, openJournal) }
        Section("Schedule — Sunday, July 19 (show floor)")
        DAY2.forEach { SlotRow(it, saved, openJournal) }

        Section("Key links")
        LINKS.forEach { l ->
            Card(Modifier.fillMaxWidth().clickable { open(ctx, l.url) }) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(l.icon, null, tint = Pink)
                    Spacer(Modifier.width(12.dp))
                    Text(l.label, Modifier.weight(1f))
                    Icon(Icons.Default.OpenInNew, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Section(text: String) {
    Text(text, Modifier.padding(top = 8.dp), color = Pink, fontWeight = FontWeight.Bold)
}

@Composable
private fun SlotRow(s: Slot, saved: Saved, openJournal: (String) -> Unit) {
    val key = "${s.time} · ${s.what}"
    val entry = saved.journals[key].orEmpty()
    Card(Modifier.fillMaxWidth().clickable { openJournal(key) }) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.time, fontWeight = FontWeight.SemiBold)
                Text(s.what)
                Text(s.where, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                if (entry.isNotBlank()) {
                    Text(
                        "✎ ${entry.trim()}",
                        color = Pink,
                        fontSize = 12.sp,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            Icon(Icons.Default.EditNote, "Open journal", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------- journal ----------

@Composable
private fun JournalScreen(key: String, saved: Saved) {
    val text = saved.journals[key].orEmpty()
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text(
            "Saves as you type.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        TextField(
            value = text,
            onValueChange = { saved.setJournal(key, it) },
            placeholder = { Text("Write it down\u2026") },
            modifier = Modifier.fillMaxSize().padding(bottom = 16.dp),
            textStyle = LocalTextStyle.current.copy(fontSize = 16.sp, lineHeight = 26.sp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedIndicatorColor = Pink,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )
    }
}

// ---------- vendors ----------

@Composable
private fun VendorsScreen(
    vendors: List<Vendor>,
    saved: Saved,
    listState: LazyListState,
    query: String,
    onQuery: (String) -> Unit,
    onOpen: (Vendor) -> Unit,
    onMap: (Vendor) -> Unit,
) {
    val shown = vendors.filter {
        query.isBlank() || it.name.contains(query, true) || it.owner.contains(query, true)
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            placeholder = { Text("Search ${vendors.size} vendors") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(shown, key = { it.name }) { VendorRow(it, saved, onOpen, onMap) }
        }
    }
}

@Composable
private fun VendorRow(v: Vendor, saved: Saved, onOpen: (Vendor) -> Unit, onMap: (Vendor) -> Unit) {
    val note = saved.notes[v.name] ?: ""
    Card(Modifier.fillMaxWidth().clickable { onOpen(v) }) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(v, 48.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(v.name, fontWeight = FontWeight.SemiBold)
                if (v.owner.isNotBlank()) {
                    Text(v.owner, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
                Text(
                    listOfNotNull(
                        v.booth?.let { "Table ${it.table}" },
                        if (v.swatches.isNotEmpty()) "${v.swatches.size} polishes" else null,
                    ).joinToString(" · "),
                    color = Pink,
                    fontSize = 12.sp,
                )
                if (note.isNotBlank()) {
                    Text("✎ $note", color = Pink, fontSize = 12.sp, maxLines = 1)
                }
            }
            MapButton(v, onMap)
            StarButton(v, saved)
        }
    }
}

@Composable
private fun MapButton(v: Vendor, onMap: (Vendor) -> Unit) {
    if (v.booth == null) return
    IconButton(onClick = { onMap(v) }) {
        Icon(
            Icons.Default.Place,
            "Show table ${v.booth.table} on the map",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StarButton(v: Vendor, saved: Saved) {
    val marked = v.name in saved.bookmarks
    IconButton(onClick = { saved.toggleBookmark(v.name) }) {
        Icon(
            if (marked) Icons.Default.Star else Icons.Outlined.StarBorder,
            contentDescription = if (marked) "Remove bookmark" else "Bookmark",
            tint = if (marked) Pink else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Avatar(v: Vendor, size: Dp) {
    val photo = if (v.img.isNotBlank()) rememberAsset(v.img, sample = 2) else null
    Box(
        Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (photo != null) {
            Image(photo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text(v.name.take(1).uppercase(), color = Pink, fontWeight = FontWeight.Bold)
        }
    }
}

/** Brand blurbs run long, so show five lines and let the page stay scannable. */
@Composable
private fun Blurb(text: String) {
    if (text.isBlank()) return
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    var overflows by remember(text) { mutableStateOf(false) }

    Text(
        text,
        fontSize = 14.sp,
        maxLines = if (expanded) Int.MAX_VALUE else 5,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
    )
    if (overflows || expanded) {
        Text(
            if (expanded) "Less" else "More description",
            Modifier.clickable { expanded = !expanded }.padding(top = 2.dp),
            color = Pink,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** A vendor's own page: who they are, what they wrote, and every polish they are bringing. */
@Composable
private fun VendorScreen(v: Vendor, saved: Saved, onMap: (Vendor) -> Unit) {
    val ctx = LocalContext.current
    var zoomed by remember { mutableStateOf<Swatch?>(null) }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(span = { GridItemSpan(2) }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(v, 72.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(v.name, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        if (v.owner.isNotBlank()) {
                            Text(v.owner, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        v.booth?.let {
                            Text("Table ${it.table}", color = Pink, fontSize = 13.sp)
                        }
                    }
                    MapButton(v, onMap)
                    StarButton(v, saved)
                }
                Blurb(v.blurb.ifBlank { v.bio })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    v.socials.forEach { (platform, url) ->
                        AssistChip(
                            onClick = { open(ctx, url) },
                            label = { Text(platform.replaceFirstChar { it.uppercase() }) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (v.website.isNotBlank()) {
                        AssistChip(onClick = { open(ctx, v.website) }, label = { Text("Shop ↗") })
                    }
                    if (v.page.isNotBlank()) {
                        AssistChip(onClick = { open(ctx, v.page) }, label = { Text("Vendor page ↗") })
                    }
                }
                OutlinedTextField(
                    value = saved.notes[v.name] ?: "",
                    onValueChange = { saved.setNote(v.name, it) },
                    label = { Text("Note (what to buy, colors, etc.)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (v.swatches.isEmpty()) "No swatches posted for this vendor"
                    else "${v.swatches.size} polishes",
                    color = Pink,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        items(v.swatches, key = { it.file }) { sw ->
            Column(Modifier.clickable { zoomed = sw }) {
                val bmp = rememberAsset(sw.file, sample = 2)
                Box(
                    Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    if (bmp != null) {
                        Image(bmp, sw.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
                if (sw.name.isNotBlank()) {
                    Text(sw.name, fontSize = 12.sp, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }

    zoomed?.let { sw ->
        Dialog(onDismissRequest = { zoomed = null }) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surface).padding(12.dp),
            ) {
                rememberAsset(sw.file)?.let {
                    Image(
                        it,
                        sw.name,
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)),
                        contentScale = ContentScale.FillWidth,
                    )
                }
                if (sw.name.isNotBlank()) {
                    Text(sw.name, Modifier.padding(top = 10.dp), fontWeight = FontWeight.SemiBold)
                }
                Text(v.name, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
        }
    }
}

// ---------- map ----------

private enum class Layer(val label: String, val res: Int) {
    Booths("Booths", R.drawable.booth_map),
    Venue("Venue", R.drawable.floorplan),
}

private const val MAP_ASPECT = 2048f / 1387f // booth_map.png
private const val PIN_ZOOM = 3.5f

@Composable
private fun MapScreen(focus: Vendor?, onFocusShown: () -> Unit) {
    var layer by rememberSaveable { mutableStateOf(Layer.Booths) }
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    fun reset() { scale = 1f; offsetX = 0f; offsetY = 0f }

    val booth = focus?.booth?.takeIf { layer == Layer.Booths }

    Column(Modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(12.dp)) {
            Layer.entries.forEachIndexed { i, l ->
                SegmentedButton(
                    selected = layer == l,
                    onClick = { layer = l; reset() },
                    shape = SegmentedButtonDefaults.itemShape(i, Layer.entries.size),
                ) { Text(l.label) }
            }
        }
        Text(
            when {
                booth != null -> "${focus.name} — table ${booth.table}"
                layer == Layer.Booths -> "Show floor tables. Pinch to zoom, drag to pan, double-tap to reset."
                else -> "Whole building: show floor is South Exhibit (11), reception is South Pavilion (12) & Promenade (32)."
            },
            Modifier.padding(horizontal = 16.dp),
            color = if (booth != null) Pink else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (booth != null) FontWeight.SemiBold else FontWeight.Normal,
            fontSize = if (booth != null) 14.sp else 12.sp,
        )
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp).background(Color(0xFF1A1A1A))
                .clipToBounds() // a zoomed map must not paint over the layer switch
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 8f)
                        offsetX += pan.x
                        offsetY += pan.y
                        onFocusShown() // touching the map drops the pin
                    }
                }
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { reset(); onFocusShown() }) },
            contentAlignment = Alignment.Center,
        ) {
            // The transformed box IS the drawn image, so a booth's fractional position maps
            // straight onto it and the pin pans and zooms with the map.
            var mapSize by remember { mutableStateOf(IntSize.Zero) }
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .wrapContentSize()
                    .aspectRatioOfMap(layer)
                    .onSizeChanged { mapSize = it }
                    .graphicsLayer(
                        scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY,
                    ),
            ) {
                Image(
                    painter = painterResource(layer.res),
                    contentDescription = layer.label,
                    modifier = Modifier.fillMaxSize(),
                )
                if (booth != null) {
                    val w = maxWidth
                    val h = maxHeight
                    Icon(
                        Icons.Default.Place,
                        "Table ${booth.table}",
                        Modifier
                            .size(28.dp)
                            .offset(x = w * booth.x - 14.dp, y = h * booth.y - 28.dp),
                        tint = Pink,
                    )
                }
            }

            LaunchedEffect(booth, mapSize) {
                if (booth != null && mapSize != IntSize.Zero) {
                    // Scaling happens about the centre, so shifting by this much lands the
                    // booth's point in the middle of the viewport.
                    scale = PIN_ZOOM
                    offsetX = (0.5f - booth.x) * mapSize.width * PIN_ZOOM
                    offsetY = (0.5f - booth.y) * mapSize.height * PIN_ZOOM
                }
            }
        }
    }
}

/** Both maps are drawn to fill their box, so pin maths only needs the booth map's shape. */
private fun Modifier.aspectRatioOfMap(layer: Layer) =
    if (layer == Layer.Booths) this.aspectRatio(MAP_ASPECT) else this

// ---------- saved ----------

@Composable
private fun SavedScreen(
    vendors: List<Vendor>,
    saved: Saved,
    openJournal: (String) -> Unit,
    openVendor: (Vendor) -> Unit,
    onMap: (Vendor) -> Unit,
) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("pbe26", Context.MODE_PRIVATE) }
    var scratch by remember { mutableStateOf(prefs.getString("scratch", "") ?: "") }
    val mine = vendors.filter { it.name in saved.bookmarks || !saved.notes[it.name].isNullOrBlank() }
    val entries = saved.journals.filterValues { it.isNotBlank() }.keys.sorted()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            OutlinedTextField(
                value = scratch,
                onValueChange = { scratch = it; prefs.edit().putString("scratch", it).apply() },
                label = { Text("Scratch notes") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )
        }
        if (entries.isNotEmpty()) {
            item { Section("Journal entries") }
            items(entries, key = { "j:$it" }) { k ->
                Card(Modifier.fillMaxWidth().clickable { openJournal(k) }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.EditNote, null, tint = Pink)
                        Spacer(Modifier.width(12.dp))
                        Text(k, Modifier.weight(1f))
                    }
                }
            }
        }
        item { Section("Vendors") }
        if (mine.isEmpty()) {
            item {
                Text(
                    "Star a vendor or add a note and it shows up here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(mine, key = { it.name }) { VendorRow(it, saved, openVendor, onMap) }
    }
}

// ---------- util ----------

private fun open(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
