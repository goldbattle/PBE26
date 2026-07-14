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

/** One line in the vendor list. Also reused by the Saved tab. */
@Composable
internal fun VendorRow(v: Vendor, saved: Saved, onOpen: (Vendor) -> Unit, onMap: (Vendor) -> Unit) {
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
                        if (v.swatches.isNotEmpty()) "${v.swatches.size} swatches" else null,
                    ).joinToString(" · "),
                    color = Pink,
                    fontSize = 12.sp,
                )
                if (note.isNotBlank()) {
                    Text("✎ $note", color = Pink, fontSize = 12.sp, maxLines = 1)
                }
            }
            NavigateButton(v, onMap)
            StarButton(v, saved)
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
    var zoomed by remember { mutableStateOf<Int?>(null) } // index into v.swatches

    // Arriving from a favourited swatch: open straight onto it.
    LaunchedEffect(openSwatch) {
        val i = v.swatches.indexOfFirst { it.file == openSwatch }
        if (i >= 0) zoomed = i
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
            Column(Modifier.clickable { zoomed = i }) {
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
    }

    zoomed?.let { start ->
        SwatchPager(v, start, saved) { zoomed = null; onSwatchShown() }
    }
}

/** Full-size swatches: swipe left/right through the vendor's swatches, pinch any of them to zoom. */
@Composable
private fun SwatchPager(v: Vendor, start: Int, saved: Saved, onClose: () -> Unit) {
    val pager = rememberPagerState(initialPage = start) { v.swatches.size }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.96f))) {
            HorizontalPager(state = pager, Modifier.fillMaxSize()) { page ->
                val sw = v.swatches[page]
                Column(
                    Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    rememberAsset(sw.file)?.let { ZoomableImage(it, sw.name) }
                    if (sw.name.isNotBlank()) {
                        Text(
                            sw.name,
                            Modifier.padding(top = 14.dp),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 18.sp,
                        )
                    }
                    Text(v.name, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
            }
            Row(
                Modifier.align(Alignment.TopEnd).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeartButton(v.swatches[pager.currentPage].file, saved)
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
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
