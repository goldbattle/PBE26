package com.aria.pbe26

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight

/**
 * A pocket guide to the Polish & Beauty Expo 2026: who is there, what they are selling, where their
 * table is, and what you decided you wanted.
 *
 * Four tabs (see [Tab]) over a single [App] composable. There is no navigation library — the whole
 * app is one screen with a handful of "what is open right now" flags, which is all four tabs and
 * two overlays are worth.
 */
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
    var openSwatch by rememberSaveable { mutableStateOf<String?>(null) } // swatch to pop up on it
    var mapFocus by rememberSaveable { mutableStateOf<String?>(null) } // vendor to pin on the map
    val vendor = vendors.firstOrNull { it.name == openVendor }

    // Hoisted so coming back from a vendor page or a journal entry lands where you left off: these
    // screens leave the composition while the page is open, and would otherwise scroll back to top.
    val vendorList = rememberLazyListState()
    val infoScroll = rememberScrollState()
    val savedList = rememberLazyListState()
    val savedSwatchRow = rememberLazyListState()
    val savedExtrasRow = rememberLazyListState()
    var query by rememberSaveable { mutableStateOf("") }

    // True while the Map tab was opened from a vendor, so back returns to the vendor list.
    var cameFromVendors by rememberSaveable { mutableStateOf(false) }

    fun showOnMap(v: Vendor) {
        openVendor = null
        journal = null
        mapFocus = v.name
        cameFromVendors = true
        tab = Tab.Map
    }

    // Back unwinds the app instead of leaving it: page -> tab -> Info -> (system handles exit).
    BackHandler(enabled = journal != null || openVendor != null || tab != Tab.Info) {
        when {
            journal != null -> journal = null
            openVendor != null -> { openVendor = null; openSwatch = null }
            tab == Tab.Map && cameFromVendors -> {
                cameFromVendors = false
                mapFocus = null
                tab = Tab.Vendors
            }
            else -> tab = Tab.Info
        }
    }

    // The map is a full-bleed page: it keeps the bottom bar and drops the title bar.
    val bare = tab == Tab.Map && journal == null && vendor == null

    Scaffold(
        topBar = {
            if (!bare) {
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
                            IconButton(onClick = { journal = null; openVendor = null; openSwatch = null }) {
                                Icon(Icons.Default.ArrowBack, "Back")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black),
                )
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF101010)) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t && journal == null && openVendor == null,
                        onClick = {
                            tab = t
                            journal = null
                            openVendor = null
                            openSwatch = null
                            cameFromVendors = false
                        },
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
                vendor != null -> VendorScreen(vendor, saved, ::showOnMap, openSwatch) { openSwatch = null }
                else -> when (tab) {
                    Tab.Info -> InfoScreen(saved, infoScroll) { journal = it }
                    Tab.Vendors -> VendorsScreen(
                        vendors, saved, vendorList, query, { query = it },
                        { openVendor = it.name }, ::showOnMap,
                    )
                    Tab.Map -> MapScreen(vendors, vendors.firstOrNull { it.name == mapFocus }) {
                        openVendor = it.name
                    }
                    Tab.Saved -> SavedScreen(
                        vendors, saved, savedList, savedSwatchRow, savedExtrasRow,
                        { journal = it }, { openVendor = it.name }, ::showOnMap,
                    ) { v, file -> openVendor = v.name; openSwatch = file }
                }
            }
        }
    }
}
