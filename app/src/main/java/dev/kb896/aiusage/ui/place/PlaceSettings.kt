package dev.kb896.aiusage.ui.place

import android.Manifest
import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kb896.aiusage.data.Pin
import dev.kb896.aiusage.place.Arrivals
import dev.kb896.aiusage.place.PlacePermission
import dev.kb896.aiusage.place.PlaceStore
import dev.kb896.aiusage.place.Tile
import dev.kb896.aiusage.place.Tiles
import dev.kb896.aiusage.ui.home.Chip
import dev.kb896.aiusage.ui.home.Label
import dev.kb896.aiusage.ui.home.LocalTone

/**
 * The settings' part of "Pin to location", in the Notes section: the real state of the position the app may read
 * (precise? all the time? Location on?), with the next step his to take; and his QUICK TILES, added, renamed, set on
 * the map, moved along the row and removed. "Home" is offered from the home Wi-Fi he marked, as a name only: its
 * place is set on the map by him, once. Nothing here is switched on for him.
 */
object PlaceSettings {
    /** A place note's second line: "Gym · 150 m", the zone alone without a name, or what it lacks to be noticed. */
    fun line(pin: Pin, possible: Boolean): String = when {
        !possible || !pin.watched -> "Needs location"
        pin.arrivedAt != 0L -> "${pin.place?.let { "$it · " }.orEmpty()}on the back now"
        else -> listOfNotNull(pin.place, Arrivals.zoneName(pin.zone)).joinToString(" · ")
    }

    fun open(activity: Activity, extra: (Intent) -> Intent) {
        runCatching { activity.startActivity(extra(Intent(activity, PlacePinActivity::class.java))) }
    }

    /** "Pin to location" for words written in the settings: the same prompt as from any app's menu. */
    fun pin(activity: Activity, text: String) = open(activity) { it.setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text) }

    /** A place note's place or zone changed: the same prompt, with "Save". */
    fun move(activity: Activity, at: Long) = open(activity) { it.putExtra(PlacePinActivity.EXTRA_PIN_AT, at) }

    /** The next step of the permission, his to give: the system's question, then the location page, then Location itself. */
    fun allow(activity: Activity) {
        when (PlacePermission.need(activity)) {
            PlacePermission.Need.PRECISE -> activity.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 71)
            PlacePermission.Need.ALWAYS -> activity.requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 72)
            PlacePermission.Need.LOCATION_OFF -> runCatching { activity.startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
            PlacePermission.Need.NOTHING -> Unit
        }
    }
}

/** The rows: the position's state, the tiles. [homeWifi]: he has marked a home Wi-Fi (HomeNet). */
@Composable
fun PlaceRows(activity: Activity, homeWifi: Boolean, fine: Boolean, always: Boolean) {
    val tone = LocalTone.current
    val store = remember { PlaceStore(activity.applicationContext) }
    var tiles by remember { mutableStateOf(store.load().tiles) }
    var on by remember { mutableStateOf(PlacePermission.locationOn(activity)) }
    // Read again whenever the page comes back (from the prompt, the permission page, Location's).
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        tiles = store.load().tiles
        on = PlacePermission.locationOn(activity)
        dev.kb896.aiusage.place.PlaceWatch.changed(activity, "back in the settings")
        onPauseOrDispose { }
    }
    fun change(next: List<Tile>) { tiles = next; store.update { it.copy(tiles = next) } }

    Label("Pin to location", size = 16, weight = 500, modifier = Modifier.padding(top = 16.dp))
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Label(PlacePermission.state(fine, always, PlacePermission.coarse(activity), on), Modifier.weight(1f), size = 13, weight = 450, color = tone.ink3, maxLines = 2)
        if (PlacePermission.need(fine, always, on) != PlacePermission.Need.NOTHING) Chip(PlacePermission.word(PlacePermission.need(fine, always, on))) { PlaceSettings.allow(activity) }
    }

    Label("Quick tiles", size = 16, weight = 500, modifier = Modifier.padding(top = 14.dp))
    var editing by remember { mutableStateOf<Long?>(null) }
    for ((i, t) in tiles.withIndex()) {
        if (editing == t.id) {
            NameRow(t.name, "Rename") { name -> change(Tiles.rename(tiles, t.id, name)); editing = null }
            continue
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable { editing = t.id }) {
                Label(t.name, size = 15, weight = 550)
                Label(if (t.set) Arrivals.zoneName(t.radius) else "No place yet", size = 12, weight = 450, color = tone.ink3)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(if (t.set) "Move" else "Set") { PlaceSettings.open(activity) { it.putExtra(PlacePinActivity.EXTRA_TILE, t.id) } }
                if (i > 0) Chip("↑") { change(Tiles.move(tiles, t.id, -1)) }
                Chip("Remove") { change(Tiles.remove(tiles, t.id)) }
            }
        }
    }
    var adding by remember { mutableStateOf(false) }
    if (adding) NameRow("", "Add") { name -> adding = false; PlaceSettings.open(activity) { it.putExtra(PlacePinActivity.EXTRA_NEW_TILE, name) } }
    else Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (tiles.size < Tiles.MOST) Chip("Add a tile") { adding = true }
        // From the home Wi-Fi he marked: the NAME only; where home is, he sets on the map.
        if (Tiles.offerHome(tiles, homeWifi) && tiles.size < Tiles.MOST) Chip("Add Home") { Tiles.add(tiles, Tiles.HOME, System.currentTimeMillis())?.let { change(it) } }
    }
}

@Composable
private fun NameRow(start: String, action: String, done: (String) -> Unit) {
    val tone = LocalTone.current
    var name by remember { mutableStateOf(start) }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f).height(40.dp).background(tone.well, RoundedCornerShape(20.dp)).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
            if (name.isEmpty()) Label("Name", size = 14, weight = 450, color = tone.ink3)
            BasicTextField(name, { name = it.take(Tiles.NAME_MOST) }, Modifier.fillMaxWidth(), singleLine = true,
                textStyle = TextStyle(color = tone.ink, fontSize = 14.sp), cursorBrush = SolidColor(tone.ink))
        }
        Chip(action, strong = true, enabled = name.isNotBlank()) { done(name) }
    }
}
