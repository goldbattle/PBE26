package com.aria.pbe26

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import org.json.JSONArray

/**
 * Everything the app knows and everything it remembers: the vendor catalogue scraped into
 * assets/vendors.json, and the user's own bookmarks, notes and journal entries.
 */

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
    val swatchers: List<String>, // who swatched this brand for the community Airtable
    val booth: Booth?, // null = not on the booth map
)

/** Table number, and where that table sits on booth_map.png (fractions of the image). */
data class Booth(val table: Int, val x: Float, val y: Float)

data class Swatch(val name: String, val file: String)

/** Split from [loadVendors] so the catalogue can be parsed and checked without a device. */
internal fun parseVendors(json: String): List<Vendor> {
    val arr = JSONArray(json)
    return (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        val s = o.getJSONObject("socials")
        val sw = o.optJSONArray("swatches")
        val by = o.optJSONArray("swatchers")
        Vendor(
            name = o.getString("name"),
            owner = o.optString("owner"),
            page = o.optString("page"),
            bio = o.optString("bio"),
            blurb = o.optString("airtableInfo"),
            website = o.optString("website"),
            img = o.optString("img"),
            socials = s.keys().asSequence().map { it to s.getString(it) }.toList(),
            swatches = (0 until (sw?.length() ?: 0)).map { j ->
                val e = sw!!.getJSONObject(j)
                Swatch(e.optString("name"), e.getString("file"))
            },
            swatchers = (0 until (by?.length() ?: 0)).map { j -> by!!.getString(j) },
            booth = if (o.has("booth")) {
                Booth(o.getInt("booth"), o.getDouble("boothX").toFloat(), o.getDouble("boothY").toFloat())
            } else {
                null
            },
        )
    }.sortedBy { it.name.lowercase() }
}

fun loadVendors(ctx: Context): List<Vendor> =
    parseVendors(ctx.assets.open("vendors.json").bufferedReader().use { it.readText() })

/**
 * Images ship in assets. 400+ swatches will not all fit in memory, so decode them downsampled and
 * keep them in a size-bounded cache.
 */
private val bitmapCache = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}

@Composable
internal fun rememberAsset(path: String, sample: Int = 1): ImageBitmap? {
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

/** Bookmarks, favourite polishes, vendor notes and journals. Everything autosaves to prefs. */
class Saved(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("pbe26", Context.MODE_PRIVATE)

    val bookmarks = mutableStateSetOf<String>().apply {
        addAll(prefs.getStringSet("bookmarks", emptySet())!!)
    }
    val favourites = mutableStateSetOf<String>().apply {
        addAll(prefs.getStringSet("favourites", emptySet())!!)
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

    fun toggleFavourite(file: String) {
        if (!favourites.remove(file)) favourites.add(file)
        prefs.edit().putStringSet("favourites", favourites.toSet()).apply()
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
