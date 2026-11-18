package dev.kb896.aiusage.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow

/**
 * The settings' glass: the colours for light and dark, and the few controls the one page is made
 * of. Glass here is Kyant's Backdrop (the same library the old bottom bar used): each pane blurs
 * and bends what is behind it, has a lit edge and a soft shadow. No accent colour at all: "on" is
 * the ink itself (light on dark, dark on light), as a stock control is. The only colour on the
 * page is the preview's and the real app icons'.
 */
@Immutable
data class Tone(
    val dark: Boolean,
    val ground: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    /** The tint laid over a pane's blurred backdrop. */
    val pane: Color,
    /** A control standing on a pane: a chip, a switch's track. */
    val well: Color,
    val edge: Color,
    /** "On": the switch's track. Neutral. */
    val on: Color,
    val onInk: Color,
) {
    companion object {
        val Dark = Tone(
            dark = true, ground = Color(0xFF060607),
            ink = Color(0xFFF4F4F2), ink2 = Color(0x9EF4F4F2), ink3 = Color(0x61F4F4F2),
            pane = Color(0x14FFFFFF), well = Color(0x1CFFFFFF), edge = Color(0x33FFFFFF),
            on = Color(0xFFE6E6E8), onInk = Color(0xFF151517),
        )
        val Light = Tone(
            dark = false, ground = Color(0xFFEEEEEC),
            ink = Color(0xFF121214), ink2 = Color(0x9E121214), ink3 = Color(0x61121214),
            pane = Color(0x8CFFFFFF), well = Color(0x14000000), edge = Color(0x1F000000),
            on = Color(0xFF2A2A2E), onInk = Color(0xFFFFFFFF),
        )
    }
}

val LocalTone = staticCompositionLocalOf { Tone.Dark }
val LocalBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

@Composable
fun Label(
    text: String, modifier: Modifier = Modifier, size: Int = 16, weight: Int = 500,
    color: Color = LocalTone.current.ink, maxLines: Int = 1,
) {
    BasicText(
        text, modifier,
        style = TextStyle(color = color, fontSize = size.sp, fontWeight = FontWeight(weight), letterSpacing = if (size >= 20) (-0.3).sp else 0.sp),
        maxLines = maxLines, overflow = TextOverflow.Ellipsis,
    )
}

/** One glass pane: what is behind it blurred and bent at its edge, a lit rim, a soft shadow. */
@Composable
fun Pane(modifier: Modifier = Modifier, radius: Dp = 30.dp, content: @Composable ColumnScope.() -> Unit) {
    val tone = LocalTone.current
    val backdrop = LocalBackdrop.current
    val shape = remember(radius) { RoundedCornerShape(radius) }
    val glass = if (backdrop != null) {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(22.dp.toPx())
                lens(14.dp.toPx(), 28.dp.toPx())
            },
            highlight = { Highlight.Default.copy(alpha = if (tone.dark) 0.55f else 0.9f) },
            shadow = { Shadow(radius = 24.dp, alpha = if (tone.dark) 0.35f else 0.12f) },
            onDrawSurface = { drawRect(tone.pane) },
        )
    } else {
        Modifier.shadow(12.dp, shape).background(if (tone.dark) Color(0xFF17171A) else Color.White, shape)
    }
    Column(modifier.fillMaxWidth().then(glass).padding(horizontal = 20.dp, vertical = 18.dp), content = content)
}

/** A section's head: its name, big, and its switch (or whatever stands at the right). */
@Composable
fun Head(title: String, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Label(title, Modifier.weight(1f), size = 21, weight = 650)
        trailing()
    }
}

/** A line under a head: a label and its control. */
@Composable
fun Line(label: String, enabled: Boolean = true, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp).heightIn(min = 36.dp).graphicsLayer { alpha = if (enabled) 1f else 0.4f },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(label, Modifier.weight(1f), size = 16, weight = 500, color = LocalTone.current.ink2)
        trailing()
    }
}

/** The switch: big enough for a thumb, yellow (or ink, in light mode) when on. */
@Composable
fun Toggle(on: Boolean, name: String, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val tone = LocalTone.current
    val at by animateFloatAsState(if (on) 1f else 0f, spring(dampingRatio = 0.72f, stiffness = 520f), label = "toggle")
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val stretch by animateFloatAsState(if (pressed) 1f else 0f, spring(stiffness = 700f), label = "press")
    Box(
        Modifier
            .size(60.dp, 36.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .semantics { contentDescription = name; stateDescription = if (on) "On" else "Off" }
            .clip(CircleShape)
            .drawBehind {
                val r = CornerRadius(size.height / 2)
                drawRoundRect(tone.well, cornerRadius = r)
                drawRoundRect(tone.on.copy(alpha = at.coerceIn(0f, 1f)), cornerRadius = r)
                drawRoundRect(tone.edge.copy(alpha = tone.edge.alpha * (1f - at.coerceIn(0f, 1f))), cornerRadius = r, style = Stroke(1.dp.toPx()))
            }
            .clickable(source, null, enabled = enabled, role = Role.Switch) { onChange(!on) },
    ) {
        val knobW = 30.dp + 6.dp * stretch
        Box(
            Modifier
                .offset(x = 3.dp + (60.dp - 6.dp - knobW) * at.coerceIn(0f, 1f), y = 3.dp)
                .size(knobW, 30.dp)
                .shadow(3.dp, CircleShape)
                .background(lerpColor(Color.White, tone.onInk, at.coerceIn(0f, 1f)), CircleShape),
        )
    }
}

private fun lerpColor(a: Color, b: Color, t: Float) = androidx.compose.ui.graphics.lerp(a, b, t)

/**
 * A slider from -1 to 1 with its middle marked: a plain well and a white knob, as the switch is; no
 * colour. [onChange] is told as the knob is dragged (some ten times a second at most) and once more
 * where it is let go. A tap on the well moves the knob there. Near the middle it rests on the middle.
 * With [from] 0 it is the same slider from 0 to 1 (an amount, as "Fluid size"): no middle, nowhere it rests.
 */
@Composable
fun Slide(value: Float, name: String, enabled: Boolean = true, modifier: Modifier = Modifier, from: Float = -1f, onChange: (Float) -> Unit) {
    val tone = LocalTone.current
    val centred = from < 0f
    val span = 1f - from
    var live by remember { androidx.compose.runtime.mutableFloatStateOf(value) }
    var dragging by remember { androidx.compose.runtime.mutableStateOf(false) }
    if (!dragging && live != value) live = value
    val told = remember { longArrayOf(0L) }
    val change by androidx.compose.runtime.rememberUpdatedState(onChange)
    val knob = 30.dp
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier.fillMaxWidth().height(36.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .semantics { contentDescription = name; stateDescription = "${(live * 100).toInt()}" }
            .clip(CircleShape)
            .drawBehind {
                val r = CornerRadius(size.height / 2)
                drawRoundRect(tone.well, cornerRadius = r)
                drawRoundRect(tone.edge, cornerRadius = r, style = Stroke(1.dp.toPx()))
                // The middle: where nothing is changed.
                if (centred) drawRect(tone.edge, Offset(size.width / 2f - 0.5.dp.toPx(), size.height * 0.3f), Size(1.dp.toPx(), size.height * 0.4f))
            },
    ) {
        val room = maxWidth - knob - 6.dp
        val roomPx = with(androidx.compose.ui.platform.LocalDensity.current) { room.toPx() }
        fun snap(v: Float) = if (centred && kotlin.math.abs(v) < 0.06f) 0f else v.coerceIn(from, 1f)
        Box(
            Modifier.fillMaxWidth().height(36.dp)
                .then(if (!enabled) Modifier else Modifier
                    .pointerInput(roomPx) {
                        detectTapGestures { at ->
                            live = snap(if (centred) ((at.x - size.width / 2f) / (roomPx / 2f)) else 0.5f + (at.x - size.width / 2f) / roomPx)
                            change(live)
                        }
                    }
                    .pointerInput(roomPx) {
                        detectHorizontalDragGestures(
                            onDragStart = { dragging = true },
                            onDragEnd = { dragging = false; live = snap(live); change(live) },
                            onDragCancel = { dragging = false; change(live) },
                        ) { c, dx ->
                            c.consume()
                            live = (live + dx / (roomPx / span)).coerceIn(from, 1f)
                            val now = android.os.SystemClock.uptimeMillis()
                            if (now - told[0] >= 100L) { told[0] = now; change(snap(live)) }
                        }
                    }),
        ) {
            Box(
                Modifier
                    .offset(x = 3.dp + room * ((live.coerceIn(from, 1f) - from) / span), y = 3.dp)
                    .size(knob, 30.dp)
                    .shadow(3.dp, CircleShape)
                    .background(Color.White, CircleShape),
            )
        }
    }
}

/** A row of choices, one chosen. */
@Composable
fun Segmented(options: List<String>, chosen: Int, enabled: Boolean = true, modifier: Modifier = Modifier, size: Int = 14, onChoose: (Int) -> Unit) {
    val tone = LocalTone.current
    Row(
        modifier.fillMaxWidth().graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .background(tone.well, RoundedCornerShape(20.dp)).padding(4.dp),
    ) {
        options.forEachIndexed { i, text ->
            val sel = i == chosen
            Box(
                Modifier.weight(1f).height(40.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (sel) (if (tone.dark) Color(0x33FFFFFF) else Color.White) else Color.Transparent)
                    .clickable(enabled = enabled, role = Role.RadioButton) { onChoose(i) },
                contentAlignment = Alignment.Center,
            ) {
                Label(text, size = size, weight = if (sel) 650 else 500, color = if (sel) tone.ink else tone.ink2)
            }
        }
    }
}

/** A small glass button with a word on it. */
@Composable
fun Chip(text: String, modifier: Modifier = Modifier, strong: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val tone = LocalTone.current
    Box(
        modifier.height(40.dp).graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .clip(CircleShape)
            .background(if (strong) (if (tone.dark) Color(0x33FFFFFF) else Color.White) else tone.well)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Label(text, size = 14, weight = if (strong) 650 else 550, color = tone.ink)
    }
}

/** The ambient ground: a soft wash of the chosen clip's colours under everything, or plain. */
fun Modifier.groundWash(tone: Tone): Modifier = drawBehind {
    // Clear under the preview, where the colours belong; denser down the page, where the words are.
    drawRect(
        Brush.verticalGradient(
            0f to tone.ground.copy(alpha = if (tone.dark) 0.25f else 0.35f),
            0.5f to tone.ground.copy(alpha = if (tone.dark) 0.5f else 0.6f),
            1f to tone.ground.copy(alpha = if (tone.dark) 0.72f else 0.78f),
        ),
        topLeft = Offset.Zero, size = Size(size.width, size.height),
    )
}

@Composable
fun Gap(h: Dp) = Spacer(Modifier.height(h))

@Composable
fun GapW(w: Dp) = Spacer(Modifier.width(w))

@Suppress("unused")
@Composable
internal fun density() = LocalContext.current.resources.displayMetrics.density

val RowGap = Arrangement.spacedBy(10.dp)
