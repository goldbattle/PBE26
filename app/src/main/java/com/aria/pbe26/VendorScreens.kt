package com.aria.pbe26

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * The Vendors tab: the searchable list, one vendor's page, and the full-screen swatch viewer.
 */

@Composable
fun VendorsScreen(
    vendors: List<Vendor>,
    saved: Saved,
    listState: LazyListState,
    query: String,
    onQuery: (String) -> Unit,
    onOpen: (Vendor) -> Unit,
    onMap: (Vendor) -> Unit,
) {
    // A search hits a vendor by their own name, or by any polish they are bringing — "heatwave"
    // should find whoever makes it.
    val shown = vendors.filter {
        query.isBlank() ||
            it.name.contains(query, true) ||
            it.owner.contains(query, true) ||
            it.swatches.any { sw -> sw.name.contains(query, true) }
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            placeholder = { Text("Search vendors and polishes") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(shown, key = { it.name }) { VendorRow(it, saved, onOpen, onMap, query) }
        }
    }
}

/**
 * One line in the vendor list. Also reused by the Saved tab, which passes no [query].
 *
 * When the row is only in the list because one of its polishes matched, say which — otherwise a
 * search for "heatwave" returns a list of brand names with no hint of why.
 */
@Composable
internal fun VendorRow(
    v: Vendor,
    saved: Saved,
    onOpen: (Vendor) -> Unit,
    onMap: (Vendor) -> Unit,
    query: String = "",
) {
    val note = saved.notes[v.name] ?: ""
    // Only when a swatch is the *reason* this row is here: if the brand itself matched, the names
    // add nothing and every row grows a paragraph.
    val byName = v.name.contains(query, true) || v.owner.contains(query, true)
    val hits = if (query.isBlank() || byName) emptyList()
    else v.swatches.filter { it.name.contains(query, true) }
    Card(Modifier.fillMaxWidth().clickable { onOpen(v) }) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                            if (v.swatches.isNotEmpty()) "${v.swatches.size} swatches" else null,
                        ).joinToString(" · "),
                        color = Pink,
                        fontSize = 12.sp,
                    )
                }
                NavigateButton(v, onMap)
                StarButton(v, saved)
            }
            // Full card width, under everything: the matches are a list of names, and squeezing them
            // into the column next to the avatar wraps them one word to a line.
            if (hits.isNotEmpty()) {
                Text(
                    hits.joinToString(", ") { it.name },
                    Modifier.padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (note.isNotBlank()) {
                Text(
                    "✎ $note",
                    Modifier.padding(top = 6.dp),
                    color = Pink,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A vendor's own page: who they are, what they wrote, and every swatch posted for them. */
@Composable
fun VendorScreen(
    v: Vendor,
    saved: Saved,
    onMap: (Vendor) -> Unit,
    openSwatch: String? = null,
    onSwatchShown: () -> Unit = {},
) {
    val ctx = LocalContext.current
    // Which list is open in the viewer, and where in it. The two lists page separately, so a
    // swipe through the swatches never runs on into the price lists.
    var zoomed by remember { mutableStateOf<Pair<List<Swatch>, Int>?>(null) }

    // Arriving from a favourite: open straight onto it, in whichever list it belongs to.
    LaunchedEffect(openSwatch) {
        listOf(v.swatches, v.extras).forEach { list ->
            val i = list.indexOfFirst { it.file == openSwatch }
            if (i >= 0) zoomed = list to i
        }
    }

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
                    NavigateButton(v, onMap)
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
                    else "${v.swatches.size} swatches",
                    color = Pink,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 6.dp),
                )
                if (v.swatchers.isNotEmpty()) {
                    Text(
                        "Swatched by ${v.swatchers.joinToString(", ")} for the community Airtable ↗",
                        Modifier.clickable { open(ctx, AIRTABLE) },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }
        }
        itemsIndexed(v.swatches, key = { _, sw -> sw.file }) { i, sw ->
            Thumbnail(sw, saved) { zoomed = v.swatches to i }
        }

        if (v.extras.isNotEmpty()) {
            item(span = { GridItemSpan(2) }) {
                Column(Modifier.padding(top = 14.dp)) {
                    Text("Shopping list & merch", color = Pink, fontWeight = FontWeight.Bold)
                    Text(
                        "What they are bringing, and what it costs.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }
            itemsIndexed(v.extras, key = { _, sw -> sw.file }) { i, sw ->
                Thumbnail(sw, saved) { zoomed = v.extras to i }
            }
        }
    }

    zoomed?.let { (list, start) ->
        SwatchPager(list, v.name, start, saved) { zoomed = null; onSwatchShown() }
    }
}

/** One tile in a vendor's grid: the picture, its heart, and its name underneath. */
@Composable
private fun Thumbnail(sw: Swatch, saved: Saved, onOpen: () -> Unit) {
    Column(Modifier.clickable(onClick = onOpen)) {
        val bmp = rememberAsset(sw.file, sample = 2)
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (bmp != null) {
                Image(bmp, sw.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            HeartButton(sw.file, saved, Modifier.align(Alignment.TopEnd))
        }
        if (sw.name.isNotBlank()) {
            Text(sw.name, fontSize = 12.sp, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** Full-size swatches: swipe left/right through the vendor's swatches, pinch any of them to zoom. */
@Composable
private fun SwatchPager(
    images: List<Swatch>,
    vendorName: String,
    start: Int,
    saved: Saved,
    onClose: () -> Unit,
) {
    val pager = rememberPagerState(initialPage = start) { images.size }
    // Full width, but only as tall as the swatch it is showing — a portrait photo and a wide one
    // should not both come wrapped in a screenful of black.
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Box {
                HorizontalPager(state = pager, Modifier.fillMaxWidth()) { page ->
                    val sw = images[page]
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        rememberAsset(sw.file)?.let { ZoomableImage(it, sw.name) }
                        if (sw.name.isNotBlank()) {
                            Text(
                                sw.name,
                                Modifier.padding(top = 12.dp),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 18.sp,
                            )
                        }
                        Text(vendorName, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    }
                }
                Row(
                    Modifier.align(Alignment.TopEnd).padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HeartButton(images[pager.currentPage].file, saved)
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
                }
            }
        }
    }
}

/**
 * Pinch to zoom, drag to pan once zoomed, double-tap to reset.
 *
 * Gestures are only swallowed when they are ours (two fingers, or a drag while zoomed in), so a
 * plain swipe still flips the pager to the next swatch.
 */
@Composable
private fun ZoomableImage(bmp: ImageBitmap, desc: String) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    fun reset() { scale = 1f; offset = Offset.Zero }

    Box(Modifier.fillMaxWidth().clipToBounds()) {
        Image(
            bmp,
            desc,
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val zoomed = scale > 1f
                            if (event.changes.size > 1 || zoomed) {
                                scale = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                                offset = if (scale > 1f) offset + event.calculatePan() else Offset.Zero
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { reset() }) }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            contentScale = ContentScale.FillWidth,
        )
    }
}
