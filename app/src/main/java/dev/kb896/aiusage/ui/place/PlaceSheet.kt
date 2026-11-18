package dev.kb896.aiusage.ui.place

import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.kb896.aiusage.place.Arrivals
import dev.kb896.aiusage.place.PlaceSearch
import dev.kb896.aiusage.place.Prompt
import dev.kb896.aiusage.ui.home.Chip
import dev.kb896.aiusage.ui.home.Label
import dev.kb896.aiusage.ui.home.LocalTone
import dev.kb896.aiusage.ui.home.Tone
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun PlaceSheet(a: PlacePinActivity) {
    val dark = (LocalConfiguration.current.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
    val tone = if (dark) Tone.Dark else Tone.Light
    CompositionLocalProvider(LocalTone provides tone) { Sheet(a, tone) }
}

@Composable
private fun Sheet(a: PlacePinActivity, tone: Tone) {
    val density = LocalDensity.current.density
    // THE OPENING, on one clock: the blur and dim from 0 ms, the sheet from 80 ms (a spring), the words settle 200 to
    // 520 ms, the pin drops at 360, the ring grows at 400. Then the map is made, under a cover.
    val rise = remember { Animatable(1f) }
    val dim = remember { Animatable(0f) }
    val words = remember { Animatable(1.2f) }
    val pinDrop = remember { Animatable(0f) }
    val ringGrow = remember { Animatable(0f) }
    val controls = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        val began = System.nanoTime()
        var last = 0L; var worst = 0L; var frames = 0
        launch { delay(80); rise.animateTo(0f, spring(dampingRatio = 0.82f, stiffness = 224f)) }
        launch { delay(200); words.animateTo(1f, tween(320, easing = FastOutSlowInEasing)) }
        launch { delay(360); pinDrop.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 500f)) }
        launch { delay(400); ringGrow.animateTo(1f, tween(260, easing = FastOutSlowInEasing)) }
        launch { dim.animateTo(1f, tween(200, easing = LinearEasing)) }
        // The window's blur follows the same clock, frame by frame; the frame gaps of the opening are said in the log.
        while (true) {
            val now = withFrameNanos { it }
            val t = (now - began) / 1_000_000L
            if (last != 0L) worst = maxOf(worst, (now - last) / 1_000_000L)
            last = now; frames++
            a.blur((100f * (t / 200f).coerceIn(0f, 1f)).toInt())
            if (t >= PlacePinActivity.OPEN_MS) break
        }
        Log.i("AiUsagePlace", "prompt: opened in $frames pictures, $worst ms at most between two")
        if (a.phase == PlacePinActivity.Phase.OPENING) a.phase = PlacePinActivity.Phase.CHOOSING
        a.makeMap()
    }
    // HIS WAY OUT: the sheet falls (220 ms), the dim and the blur go with it. THE PIN: the controls go (120 ms), the words stay.
    LaunchedEffect(a.phase) {
        when (a.phase) {
            PlacePinActivity.Phase.CLOSING -> {
                launch { rise.animateTo(1f, tween(PlacePinActivity.CLOSE_MS.toInt(), easing = FastOutSlowInEasing)) }
                launch { dim.animateTo(0f, tween(PlacePinActivity.CLOSE_MS.toInt())) }
                val began = System.nanoTime()
                while (true) {
                    val t = (withFrameNanos { it } - began) / 1_000_000L
                    a.blur((100f * (1f - t / PlacePinActivity.CLOSE_MS.toFloat())).coerceIn(0f, 100f).toInt())
                    if (t >= PlacePinActivity.CLOSE_MS) break
                }
            }
            PlacePinActivity.Phase.LEAVING -> {
                controls.animateTo(0f, tween(120))
                // The pin's own window takes over the blur; ours lets its dim go meanwhile.
                delay(PlacePinActivity.HANDOVER_MS)
                launch { dim.animateTo(0f, tween(150)) }
            }
            else -> Unit
        }
    }

    BoxWithConstraints(
        Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = dim.value * if (a.blurOn) 0.22f else 0.5f))
            .pointerInput(Unit) { detectTapGestures { a.cancel() } }
            .imePadding(),
    ) {
        val box = Prompt.sheet(maxWidth.value, maxHeight.value)
        val sheetH = box.height.dp
        val shape = RoundedCornerShape(36.dp)
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = Prompt.MARGIN.dp)
                .width(box.width.dp)
                .graphicsLayer { translationY = rise.value * (sheetH.toPx() + 40.dp.toPx()) }
                .clip(shape)
                .background(Color(if (tone.dark) 0xB3161618 else 0xCCF6F6F4).copy(alpha = (if (tone.dark) 0.70f else 0.80f) * (0.25f + 0.75f * controls.value)), shape)
                .border(1.dp, tone.edge.copy(alpha = tone.edge.alpha * controls.value), shape)
                // Taps on the sheet are the sheet's, never the blur's way out.
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The handle and the words: a stroke down here closes it.
            var pulled by remember { mutableFloatStateOf(0f) }
            Column(
                Modifier.fillMaxWidth().pointerInput(Unit) {
                    detectVerticalDragGestures(onDragEnd = { if (pulled > 120.dp.toPx()) a.cancel(); pulled = 0f }) { c, dy -> c.consume(); pulled = (pulled + dy).coerceAtLeast(0f) }
                },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.padding(top = 2.dp).size(36.dp, 4.dp).graphicsLayer { alpha = controls.value }.background(tone.ink3, CircleShape))
                Spacer(Modifier.height(10.dp))
                val head = a.title?.let { if (a.mode == PlacePinActivity.Mode.TILE || a.mode == PlacePinActivity.Mode.NEW_TILE) "Where is $it?" else it } ?: a.text
                val shot = a.picture
                if ((a.mode == PlacePinActivity.Mode.PIN || a.mode == PlacePinActivity.Mode.MOVE_PIN) && (shot != null || a.reading || a.image != null)) {
                    // A PICTURE'S NOTE: the picture, small, where his words would be (its place held while it is read),
                    // and its caption, if any, beside it.
                    Row(
                        Modifier.padding(horizontal = 12.dp).fillMaxWidth().graphicsLayer { scaleX = words.value; scaleY = words.value },
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val wide = if (shot != null && shot.height > 0) (PICTURE_DP * shot.width / shot.height).coerceIn(24f, PICTURE_DP * 1.9f) else PICTURE_DP
                        if (shot != null) androidx.compose.foundation.Image(
                            shot.asImageBitmap(), null, Modifier.size(wide.dp, PICTURE_DP.dp).clip(RoundedCornerShape(12.dp)),
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        ) else Box(Modifier.size(PICTURE_DP.dp).clip(RoundedCornerShape(12.dp)).background(tone.ink3.copy(alpha = 0.18f)))
                        if (a.text.isNotBlank()) Label(a.text, Modifier.weight(1f, fill = false), size = 17, weight = 550, maxLines = 2)
                    }
                } else Label(head, Modifier.padding(horizontal = 12.dp).fillMaxWidth().graphicsLayer { scaleX = words.value; scaleY = words.value }, size = 17, weight = 550, maxLines = 2)
            }
            Spacer(Modifier.height(10.dp))
            val folded = a.query.isNotBlank() && (a.hits.isNotEmpty() || a.searchNote != null)
            Column(Modifier.fillMaxWidth().graphicsLayer { alpha = controls.value }, horizontalAlignment = Alignment.CenterHorizontally) {
                if (a.mode == PlacePinActivity.Mode.PIN && a.tiles.isNotEmpty() && !folded) {
                    TilesRow(a)
                    Spacer(Modifier.height(10.dp))
                }
                if (!folded) {
                    Well(a, tone, pinDrop.value, ringGrow.value, density)
                    Spacer(Modifier.height(10.dp))
                    ZoneSlider(a, tone)
                    Spacer(Modifier.height(10.dp))
                } else Results(a, tone)
                SearchField(a, tone)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val shake = remember { Animatable(0f) }
                    LaunchedEffect(a.shakes) { if (a.shakes > 0) for (x in listOf(8f, -8f, 5f, -5f, 0f)) shake.animateTo(x, tween(45)) }
                    Chip(if (a.hereBusy) "Here…" else "Here", Modifier.offset(x = shake.value.dp)) { a.goHere() }
                    if (a.mode == PlacePinActivity.Mode.PIN) {
                        if (a.naming) NameField(a, tone, Modifier.weight(1f))
                        else Chip("Save as tile") { a.naming = true }
                    }
                }
                Spacer(Modifier.height(10.dp))
                val word = a.word()
                // "Pin" before any place is chosen (a first opening shows the world): faint, and a tap shakes "Here".
                val faint = !a.positioned && (word == "Pin" || word == "Save" || a.settingTile != null)
                Box(
                    Modifier.fillMaxWidth().height(56.dp).graphicsLayer { alpha = if (faint) 0.4f else 1f }.clip(RoundedCornerShape(28.dp)).background(tone.on)
                        .semantics { contentDescription = word }
                        .clickable(role = Role.Button) { a.button() },
                    contentAlignment = Alignment.Center,
                ) { Label(word, size = 17, weight = 650, color = tone.onInk) }
            }
        }
    }
}

@Composable
private fun TilesRow(a: PlacePinActivity) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (t in a.tiles) Chip(if (t.set) t.name else "${t.name} · set", strong = a.settingTile?.id == t.id) { a.tile(t) }
    }
}

/** The map's well: the map (once made, under a cover of the well's colour until its first whole picture), our pin and ring, the credit. */
@Composable
private fun Well(a: PlacePinActivity, tone: Tone, drop: Float, grow: Float, density: Float) {
    val wellColor = if (tone.dark) Color(0xFF1B1B1E) else Color(0xFFE2E2DF)
    val cover by animateFloatAsState(if (a.mapShown) 0f else 1f, tween(260), label = "cover")
    val lift by animateFloatAsState(if (a.moving) 8f else 0f, spring(stiffness = 600f), label = "lift")
    // The figure on the rim: while the zone changes and 1.2 s after (not as the sheet opens).
    var figure by remember { mutableStateOf(false) }
    var firstZone by remember { mutableStateOf(true) }
    LaunchedEffect(a.radius) { if (firstZone) { firstZone = false; return@LaunchedEffect }; figure = true; delay(1_200L); figure = false }
    Box(Modifier.fillMaxWidth().height(236.dp).clip(RoundedCornerShape(28.dp)).background(wellColor)) {
        a.map?.let { m -> AndroidView(factory = { m.view }, modifier = Modifier.fillMaxSize()) }
        if (cover > 0f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = cover }.background(wellColor))
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height / 2f)
            // The ring: what the map says the zone is now; before the map, its resting size.
            val r = (if (a.ringPx > 0f) a.ringPx else Prompt.REST_DP / 2f * density) * grow
            if (r > 0f) {
                drawCircle(Color.White.copy(alpha = 0.10f), r, c)
                drawCircle(Color.White.copy(alpha = 0.85f), r, c, style = Stroke(1.5.dp.toPx()))
            }
            // The pin: the back's own drop with a hole, white, 28 dp; it drops in, and lifts while the map moves.
            if (drop > 0f) {
                val pr = 9.dp.toPx()
                val tipY = c.y - lift.dp.toPx() - (1f - drop) * 40.dp.toPx()
                val cy = tipY - pr * 2.1f
                val p = Path().apply {
                    addOval(androidx.compose.ui.geometry.Rect(Offset(c.x, cy), pr))
                    moveTo(c.x - pr * 0.86f, cy + pr * 0.5f); lineTo(c.x, tipY); lineTo(c.x + pr * 0.86f, cy + pr * 0.5f); close()
                }
                drawCircle(Color.Black.copy(alpha = 0.25f), 3.dp.toPx() + lift / 4f, Offset(c.x, c.y + 1.dp.toPx()))
                drawPath(p, Color.White.copy(alpha = drop.coerceIn(0f, 1f)))
                drawCircle(wellColor.copy(alpha = drop.coerceIn(0f, 1f)), pr * 0.42f, Offset(c.x, cy))
            }
        }
        // The figure on the rim, while the zone changes and a moment after.
        if (figure) {
            val up = ((if (a.ringPx > 0f) a.ringPx else Prompt.REST_DP / 2f * density) / density).dp
            Box(Modifier.align(Alignment.Center).offset(y = -up - 12.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Label(Arrivals.zoneName(a.radius), size = 12, weight = 600, color = Color.White)
            }
        }
        Label(PlaceMap.CREDIT, Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 6.dp), size = 9, weight = 500, color = if (tone.dark) Color(0x99FFFFFF) else Color(0x99000000))
    }
}

/** The zone: 100 m to 2 km in the slider's steps, a plain well and a white knob as the settings' slider. */
@Composable
private fun ZoneSlider(a: PlacePinActivity, tone: Tone) {
    val steps = Arrivals.ZONES.size - 1
    var live by remember { mutableFloatStateOf(Prompt.step(a.radius).toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    if (!dragging && live.toInt() != Prompt.step(a.radius)) live = Prompt.step(a.radius).toFloat()
    BoxWithConstraints(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(36.dp).clip(CircleShape).background(tone.well)
            .semantics { contentDescription = "Zone"; },
    ) {
        val room = maxWidth - 36.dp
        val roomPx = with(LocalDensity.current) { room.toPx() }
        Box(
            Modifier.fillMaxSize()
                .pointerInput(roomPx) { detectTapGestures { at -> live = ((at.x - 18.dp.toPx()) / roomPx * steps).coerceIn(0f, steps.toFloat()); a.zone(kotlin.math.round(live).toInt(), true) } }
                .pointerInput(roomPx) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = { dragging = false; live = kotlin.math.round(live); a.zone(live.toInt(), true) },
                        onDragCancel = { dragging = false; a.zone(kotlin.math.round(live).toInt(), true) },
                    ) { c, dx ->
                        c.consume()
                        live = (live + dx / roomPx * steps).coerceIn(0f, steps.toFloat())
                        val step = kotlin.math.round(live).toInt()
                        if (Prompt.zoneAt(step) != a.radius) a.zone(step, false)
                    }
                },
        ) {
            Box(Modifier.offset(x = 3.dp + room * (live / steps), y = 3.dp).size(30.dp).background(Color.White, CircleShape))
        }
    }
}

@Composable
private fun SearchField(a: PlacePinActivity, tone: Tone) {
    Box(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(24.dp)).background(tone.well).padding(horizontal = 18.dp), contentAlignment = Alignment.CenterStart) {
        if (a.query.isEmpty()) Label(a.placeName ?: "Search a place", size = 15, weight = 500, color = if (a.placeName != null) tone.ink2 else tone.ink3)
        BasicTextField(
            a.query, { a.search(it) }, Modifier.fillMaxWidth(),
            textStyle = TextStyle(color = tone.ink, fontSize = 15.sp, fontWeight = FontWeight(500)),
            singleLine = true, cursorBrush = SolidColor(tone.ink),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { a.hits.firstOrNull()?.let { a.choose(it) } }),
        )
    }
}

/** Up to four results while he types, the nearest first as Photon gives them, OpenStreetMap credited. */
@Composable
private fun Results(a: PlacePinActivity, tone: Tone) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        for (h in a.hits) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable { a.choose(h) }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Label(h.name, size = 15, weight = 600)
                if (h.line.isNotEmpty()) Label(h.line, size = 13, weight = 450, color = tone.ink2)
            }
        }
        a.searchNote?.let { Label(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), size = 14, weight = 500, color = tone.ink2) }
        Label(PlaceSearch.CREDIT, Modifier.padding(horizontal = 16.dp, vertical = 2.dp), size = 10, weight = 500, color = tone.ink3)
    }
}

/** "Save as tile": the name, and Save. */
@Composable
private fun NameField(a: PlacePinActivity, tone: Tone, modifier: Modifier) {
    var name by remember { mutableStateOf(a.placeName.orEmpty()) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f).height(40.dp).clip(CircleShape).background(tone.well).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
            if (name.isEmpty()) Label("Name", size = 14, weight = 500, color = tone.ink3)
            BasicTextField(name, { name = it.take(dev.kb896.aiusage.place.Tiles.NAME_MOST) }, Modifier.fillMaxWidth(),
                textStyle = TextStyle(color = tone.ink, fontSize = 14.sp), singleLine = true, cursorBrush = SolidColor(tone.ink),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { a.saveTile(name) }))
        }
        Chip("Save", strong = true, enabled = name.isNotBlank()) { a.saveTile(name); a.hideKeyboard() }
    }
}

/** A shared picture's height in the sheet, where his words would be (dp). */
private const val PICTURE_DP = 64f
