package com.aria.pbe26

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The theme, and the small widgets that show up on more than one screen. */

internal val Pink = Color(0xFFF48FB1)

internal val PbeDark = darkColorScheme(
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

/** Hand a url (or a geo: uri) to whatever app the phone uses for it. */
internal fun open(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

@Composable
internal fun Section(text: String) {
    Text(text, Modifier.padding(top = 8.dp), color = Pink, fontWeight = FontWeight.Bold)
}

/** The vendor's logo, or their initial if they never posted one. */
@Composable
internal fun Avatar(v: Vendor, size: Dp) {
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

/** Jump to this vendor's table on the map. Vendors without a booth don't get one. */
@Composable
internal fun NavigateButton(v: Vendor, onMap: (Vendor) -> Unit) {
    if (v.booth == null) return
    IconButton(onClick = { onMap(v) }) {
        Icon(
            Icons.Default.Place,
            "Show table ${v.booth.table} on the map",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Bookmark a vendor. The star is what puts them in the Saved tab. */
@Composable
internal fun StarButton(v: Vendor, saved: Saved) {
    val marked = v.name in saved.bookmarks
    IconButton(onClick = { saved.toggleBookmark(v.name) }) {
        Icon(
            if (marked) Icons.Default.Star else Icons.Outlined.StarBorder,
            contentDescription = if (marked) "Remove bookmark" else "Bookmark",
            tint = if (marked) Pink else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Favourite a swatch. The heart is what puts it in the Saved tab. */
@Composable
internal fun HeartButton(file: String, saved: Saved, modifier: Modifier = Modifier) {
    val loved = file in saved.favourites
    IconButton(onClick = { saved.toggleFavourite(file) }, modifier = modifier) {
        Icon(
            if (loved) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            contentDescription = if (loved) "Remove from saved swatches" else "Save this swatch",
            tint = if (loved) Pink else Color.White.copy(alpha = 0.85f),
        )
    }
}

/** Brand blurbs run long, so show five lines and let the page stay scannable. */
@Composable
internal fun Blurb(text: String) {
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
