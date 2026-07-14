package com.aria.pbe26

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The Info tab: when and where the expo is, what is on at what time, and the links worth having.
 * The schedule doubles as the journal — every slot opens a note you can write in.
 */

// ---------- venue content ----------

private data class Link(val label: String, val url: String, val icon: ImageVector)

private const val ADDRESS = "18451 Convention Center Dr, Tinley Park, IL 60477"

/** Where every swatch photo and brand blurb in this app came from; credited on each vendor too. */
internal const val AIRTABLE = "https://airtable.com/embed/appHCStOEjlIqVBea/shrPc6rx46s6keM1T"

private val LINKS = listOf(
    Link("Tickets (Eventbrite)", "https://polishandbeautyexpo2026.eventbrite.com", Icons.Default.ConfirmationNumber),
    Link("Official site", "https://polishandbeautyexpo.com/", Icons.Default.Language),
    Link("Full schedule", "https://polishandbeautyexpo.com/schedule/", Icons.Default.Schedule),
    Link("Vendor list", "https://polishandbeautyexpo.com/more-about-vendors/", Icons.Default.Storefront),
    Link("Community swatch Airtable", AIRTABLE, Icons.Default.Palette),
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

// ---------- screens ----------

@Composable
fun InfoScreen(saved: Saved, openJournal: (String) -> Unit) {
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
                        Text(
                            "Tap for directions",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
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

        Section("Credits")
        Card(Modifier.fillMaxWidth().clickable { open(ctx, AIRTABLE) }) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Favorite, null, tint = Pink)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Swatches by the community Airtable", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Every swatch photo and brand description in this app was collected by the " +
                            "swatchers who built the PBE 2026 community swatch Airtable. Go thank " +
                            "them — and add your own.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                }
                Icon(Icons.Default.OpenInNew, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
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

/** One journal entry, keyed by its schedule slot. Saves on every keystroke. */
@Composable
fun JournalScreen(key: String, saved: Saved) {
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
            placeholder = { Text("Write it down…") },
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
