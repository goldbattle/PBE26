package com.aria.pbe26

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The Saved tab: one place for everything the user marked — scratch notes, journal entries,
 * favourited polishes, and the vendors they starred or wrote a note about.
 */
@Composable
fun SavedScreen(
    vendors: List<Vendor>,
    saved: Saved,
    openJournal: (String) -> Unit,
    openVendor: (Vendor) -> Unit,
    onMap: (Vendor) -> Unit,
    openSwatch: (Vendor, String) -> Unit,
) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("pbe26", Context.MODE_PRIVATE) }
    var scratch by remember { mutableStateOf(prefs.getString("scratch", "") ?: "") }
    val mine = vendors.filter { it.name in saved.bookmarks || !saved.notes[it.name].isNullOrBlank() }
    val entries = saved.journals.filterValues { it.isNotBlank() }.keys.sorted()
    val loved = vendors.flatMap { v -> v.swatches.filter { it.file in saved.favourites }.map { v to it } }

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

        if (loved.isNotEmpty()) {
            item { Section("Saved polishes") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(loved, key = { (_, sw) -> sw.file }) { (v, sw) ->
                        Column(Modifier.width(110.dp).clickable { openSwatch(v, sw.file) }) {
                            rememberAsset(sw.file, sample = 4)?.let {
                                Image(
                                    it,
                                    sw.name,
                                    Modifier.size(110.dp).clip(RoundedCornerShape(10.dp)),
                                    contentScale = ContentScale.Crop,
                                )
                            }
                            Text(
                                sw.name.ifBlank { v.name },
                                fontSize = 11.sp,
                                maxLines = 2,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            Text(
                                v.name,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 10.sp,
                                maxLines = 1,
                            )
                        }
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
