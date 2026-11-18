package dev.kb896.aiusage.rear

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import dev.kb896.aiusage.Panel
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class RearCornersTest {
    private val rear = RearGeometry(976, 596, 296, 101, 440)
    private val W = rear.widthPx
    private val H = rear.heightPx
    private val w = W.toFloat()
    private val h = H.toFloat()
    private val r = rear.cornerPx.toFloat()

    /** The code's own painter of what lies over everything on the back, if it has one (c1dd06a had none). */
    private val mask: java.lang.reflect.Method? = RearShape::class.java.methods.firstOrNull { it.name == "drawMask" }

    // ---- what is on the back ----

    enum class Kind { OTHERS, MOON, PICKER, CHARGE, MUSIC, TIMER, NAVIGATION, CALL, USAGE, NOTES, ARRIVAL, SHADE, POP }

    private fun z(k: Kind) = when (k) {
        Kind.OTHERS -> Int.MIN_VALUE
        Kind.MOON -> ShellLayer.Z_SCENE
        Kind.PICKER -> ShellLayer.Z_PICKER
        Kind.CHARGE -> ShellLayer.Z_CHARGE_PAGE
        Kind.MUSIC -> ShellLayer.Z_MUSIC
        Kind.TIMER, Kind.NAVIGATION, Kind.CALL -> ShellLayer.Z_LIVE
        // The arrival page is a ShellPage as usage and the notes are, at their Z (it stands over them: RearNav takes them down under it).
        Kind.USAGE, Kind.NOTES, Kind.ARRIVAL -> ShellLayer.Z_USAGE
        Kind.SHADE -> ShellLayer.Z_PANES
        Kind.POP -> ShellLayer.Z_BANNER
    }

    private fun liveKind(l: RearNav.Live) = when (l) {
        RearNav.Live.NAVIGATION -> Kind.NAVIGATION; RearNav.Live.CHARGE -> Kind.CHARGE; RearNav.Live.MUSIC -> Kind.MUSIC; RearNav.Live.TIMER -> Kind.TIMER; RearNav.Live.CALL -> Kind.CALL
    }

    /** What the back shows in [s], the lowest first. */
    private fun stack(s: RearNav.State): List<Kind> {
        if (!s.lit) return emptyList()
        val k = ArrayList<Kind>()
        if (s.ours) {
            k += Kind.MOON
            // The face picker (feat/face-picker): the face as its card, the other cards on the picker's layer over it.
            if (s.picker) k += Kind.PICKER
            if (s.liveShown) k += liveKind(s.live!!)
            if (s.row == RearNav.Row.USAGE) k += Kind.USAGE
            if (s.notes) k += Kind.NOTES
            if (s.arrivalUp) k += Kind.ARRIVAL
        } else k += Kind.OTHERS
        if (s.shade) k += Kind.SHADE
        if (s.pop) k += Kind.POP
        return k
    }

    // ---- pictures: plain colours for the checks (any square corner shows), the pages' own painters for the sheet ----

    private var real = false
    private val pics = HashMap<String, Bitmap>()
    private val frameBitmaps = ArrayList<Bitmap>()
    private fun blank() = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888).also { frameBitmaps += it }
    private fun freeFrame() {
        // Not recycled by hand: some pictures are made once per case and drawn in several of its frames. The Java side
        // of a picture is tiny, so the heap seldom fills and the collector seldom runs while native pixels and canvases
        // wait for it: every 10 frames it is asked to run.
        frameBitmaps.clear()
        if (++framesDone % 10 == 0) System.gc()
    }
    private var framesDone = 0
    private fun flat(color: Int) = blank().apply { eraseColor(color) }

    private val moonFrame: Bitmap? by lazy {
        System.getenv("REAR_CORNERS_FIXTURE")?.let(BitmapFactory::decodeFile)
            ?.takeIf { it.width == W && it.height == H }
    }

    private fun pic(k: Kind): Bitmap = pics.getOrPut("$real/$k") {
        when (k) {
            Kind.OTHERS -> flat(0xFF808080.toInt()).also { PageCorners.cut(Canvas(it), w, h, r) }   // another app keeps its own shape
            Kind.MOON -> (if (real) moonFrame else null) ?: flat(0xFF4A5A78.toInt())
            Kind.PICKER -> error("the picker is drawn by its own painter (pickerLayers)")
            Kind.CHARGE -> flat(0xFF2BB673.toInt())
            Kind.MUSIC -> flat(0xFFB0502A.toInt())
            Kind.NAVIGATION -> if (real) blank().also { NavFace.draw(Canvas(it), w, h, NavFace.demo()) } else flat(0xFF164D27.toInt())
            Kind.TIMER -> flat(0xFF1E6FD9.toInt())
            Kind.CALL -> flat(0xFF138A3A.toInt())
            Kind.USAGE -> flat(0xFFD9A441.toInt())
            Kind.NOTES -> if (real) blank().also { note(Canvas(it), 0) } else flat(0xFF7A4A2A.toInt())
            Kind.ARRIVAL -> flat(0xFF3A6A2A.toInt())
            Kind.SHADE -> pile(pileScene(), 1f)
            Kind.POP -> if (real) blank().also { NoticePop.draw(Canvas(it), w, h, "Alex", "Are you coming tonight? We leave at eight", 0xFF25D366.toInt(), null) }
                else flat(0xFFE0E0E0.toInt())
        }
    }

    private val noteColors = intArrayOf(0xFF7A4A2A.toInt(), 0xFF2A6A7A.toInt(), 0xFF7A2A4A.toInt())
    private fun note(c: Canvas, n: Int) {
        if (real) PinFace.draw(c, w, h, listOf("Pick up the parcel (Demo)", "Second made-up note (Demo)", "Third made-up note (Demo)")[n % 3], "3 Nov", n, 3)
        else c.drawRect(0f, 0f, w, h, Paint().apply { color = noteColors[n % 3] })   // its own place only (drawColor would fill the canvas)
    }

    private val items = listOf(
        NoticeShade.Item("a", "com.whatsapp", "WhatsApp", "Alex", "Are you coming tonight? We leave at eight", color = 0xFF25D366.toInt()),
        NoticeShade.Item("b", "org.telegram.messenger", "Telegram", "Sam", "Can you pick up bread on the way home?", color = 0xFF2AABEE.toInt()),
        NoticeShade.Item("c", "com.instagram.android", "Instagram", "Instagram", "Someone liked your photo", color = 0xFFE1306C.toInt()),
    )
    private fun pileScene(top: NoticePages.Page? = null, empty: Boolean = false, label: NoticeThrow.Label? = null): NoticePages.Scene {
        if (empty) return NoticePages.Scene(emptyList(), emptyList(), 0f, true)
        val pages = arrayListOf(NoticePages.Page(items[2], 0f, 0f, 1f), NoticePages.Page(items[1], 0f, 0f, 1f))
        pages += top ?: NoticePages.Page(items[0], 0f, 0f, 1f)
        return NoticePages.Scene(pages, items, 2f, false, label)
    }

    /** A throw's label on its own layer (ShellNotices' ThrowLabel), [t] ms into it. */
    private fun labelPic(kind: NoticeThrow.Kind, t: Long): Bitmap = blank().also { b ->
        NoticeThrow.labelAt(kind, t)?.let { NoticeThrowArt().draw(Canvas(b), h, it) }
    }

    /** The notifications' pages as their layer has them: in panel units, [progress] of the way down. */
    private fun pile(s: NoticePages.Scene, progress: Float): Bitmap = blank().also {
        val c = Canvas(it)
        c.scale(rear.fitX, rear.fitY)
        NoticePagesArt().draw(c, rear, s, progress, words = false, icon = { null })
    }

    private fun turn(from: Int, to: Int, dir: Int, at: Float, skip: Int = -9): Bitmap = blank().also {
        val c = Canvas(it)
        NoteTurnArt.draw(c, w, h, PageCorners.radius(rear, w), from, to, dir, at) { n -> if (n != skip) note(c, n) }
    }

    // The notes put in order: the carousel (NoteCarousel moves it, NoteArrangeArt draws it on the notes page in its place).
    private val arrangePins = listOf(
        dev.kb896.aiusage.data.Pin("Pick up the parcel (Demo)", 101L), dev.kb896.aiusage.data.Pin("Second made-up note (Demo)", 102L),
        dev.kb896.aiusage.data.Pin("Third made-up note (Demo)", 103L), dev.kb896.aiusage.data.Pin("Fourth made-up note (Demo)", 104L),
    )
    private val arrangeArt = NoteArrangeArt()
    private fun carouselPic(car: NoteCarousel, skip: Long = -1L): Bitmap = blank().also {
        // A card left out keeps the others' places and numbers: only its name is one the carousel does not have.
        val pins = arrangePins.map { p -> if (p.at == skip) p.copy(at = -77L) else p }
        arrangeArt.draw(Canvas(it), w, h, car, pins, PageCorners.radius(rear, w))
    }
    private fun carouselFrame(car: NoteCarousel, dim: Boolean): Frame {
        val base = restLayers(listOf(Kind.MOON), dim)
        val layers = base + ours("notes", z(Kind.NOTES), carouselPic(car))
        val moving = car.placed(w, h).filter { abs(it.scale - 1f) > 0.001f || abs(it.cx - w / 2f) > 0.5f || abs(it.cy - h / 2f) > 0.5f }.map { p ->
            Moving("card ${p.id}", p.cx - w * p.scale / 2f, p.cy - h * p.scale / 2f, w * p.scale, h * p.scale, r * p.scale) {
                compose(base + ours("notes", z(Kind.NOTES), carouselPic(car, skip = p.id)))
            }
        }
        return Frame(layers, moving)
    }

    private val beamArt = PinBeamArt()
    private fun beam(b: PinBeam.Back): Bitmap = blank().also { ShellBeam.drawBack(Canvas(it), beamArt, pic(Kind.NOTES), w, h, b, 1f, r) }

    // ---- the composer ----

    /** [scale]: the layer scaled about its top-left corner, standing at ([x], [y]); [radius] in its own space (scaled with it, as SurfaceFlinger does). */
    class Layer(val name: String, val z: Int, val pic: Bitmap, val x: Float, val y: Float, val radius: Float, val alpha: Float = 1f, val scale: Float = 1f)

    /** A layer of ours at ([x], [y]) with the radius the code gives it there. */
    private fun ours(name: String, z: Int, pic: Bitmap, x: Float = 0f, y: Float = 0f, alpha: Float = 1f) =
        Layer(name, z, pic, x, y, RearShape.cornerAt(rear, x, y), alpha)

    private val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val filter = Paint(Paint.FILTER_BITMAP_FLAG)
    private val path = Path()

    private fun compose(layers: List<Layer>): Bitmap {
        val out = blank()
        val c = Canvas(out)
        c.drawColor(Color.BLACK)
        for (l in layers.sortedBy { it.z }) {
            val save = c.saveLayerAlpha(0f, 0f, w, h, (l.alpha * 255).toInt().coerceIn(0, 255))
            if (l.scale == 1f) c.drawBitmap(l.pic, l.x, l.y, null)
            else { c.save(); c.translate(l.x, l.y); c.scale(l.scale, l.scale); c.drawBitmap(l.pic, 0f, 0f, filter); c.restore() }
            if (l.radius > 0f) {
                // SurfaceFlinger's rounded corner: the layer's crop (its own size) at its place, cut round.
                path.rewind(); path.fillType = Path.FillType.EVEN_ODD
                path.addRect(-2f, -2f, w + 2f, h + 2f, Path.Direction.CW)
                val sr = l.radius * l.scale
                path.addRoundRect(l.x, l.y, l.x + w * l.scale, l.y + h * l.scale, sr, sr, Path.Direction.CW)
                c.drawPath(path, clear)
            }
            c.restoreToCount(save)
        }
        mask?.let { m -> val b = blank(); m.invoke(RearShape, Canvas(b), w, h, r); c.drawBitmap(b, 0f, 0f, null) }
        return out
    }

    // ---- the checks ----

    /** A page that is off its place: its own rectangle and radius, and the back as it would be without it. */
    class Moving(
        val name: String, val left: Float, val top: Float, val pw: Float, val ph: Float, val pr: Float, val soft: Float = 1.5f,
        /** Which of its corners are looked at (0 top-left, 1 top-right, 2 bottom-left, 3 bottom-right). */
        val only: Set<Int> = setOf(0, 1, 2, 3),
        val without: () -> Bitmap,
    )

    private fun lit(p: Int) = max(Color.red(p), max(Color.green(p), Color.blue(p))) > 6

    private val cornerNames = listOf("top-left", "top-right", "bottom-left", "bottom-right")

    /** Whether the point is inside the display's rounded shape by more than [margin]. */
    private fun inDisplay(px: Float, py: Float, margin: Float): Boolean {
        if (px < margin || py < margin || px > w - margin || py > h - margin) return false
        val cx = if (px < r) r else if (px > w - r) w - r else return true
        val cy = if (py < r) r else if (py > h - r) h - r else return true
        return hypot(px - cx, py - cy) <= r - margin
    }

    /** Pixels lit outside the display's corners (by more than a pixel), per corner. */
    private fun outsideDisplay(b: Bitmap): List<String> {
        val out = ArrayList<String>()
        val n = ceil(r).toInt()
        for ((k, name) in cornerNames.withIndex()) {
            val right = k % 2 == 1; val bottom = k >= 2
            val cx = if (right) w - r else r; val cy = if (bottom) h - r else r
            var bad = 0
            for (j in 0 until n) for (i in 0 until n) {
                val x = if (right) W - 1 - i else i; val y = if (bottom) H - 1 - j else j
                if (hypot(x + 0.5f - cx, y + 0.5f - cy) > r + 1f && lit(b.getPixel(x, y))) bad++
            }
            if (bad > 0) out += "lit outside the display's $name corner ($bad px)"
        }
        return out
    }

    /** A page's corners in view that are not round: what shows outside its arc is not what is under it. */
    private fun squareCorners(b: Bitmap, m: Moving): List<String> {
        val out = ArrayList<String>()
        var without: Bitmap? = null
        val n = ceil(m.pr).toInt()
        for ((k, name) in cornerNames.withIndex()) {
            if (k !in m.only) continue
            val right = k % 2 == 1; val bottom = k >= 2
            val cx = if (right) m.left + m.pw - m.pr else m.left + m.pr
            val cy = if (bottom) m.top + m.ph - m.pr else m.top + m.pr
            var seen = 0; var bad = 0
            for (j in 0 until n) for (i in 0 until n) {
                val fx = if (right) m.left + m.pw - 1 - i else m.left + i
                val fy = if (bottom) m.top + m.ph - 1 - j else m.top + j
                val x = Math.floor(fx.toDouble()).toInt(); val y = Math.floor(fy.toDouble()).toInt()
                if (x < 0 || y < 0 || x >= W || y >= H) continue
                val px = x + 0.5f; val py = y + 0.5f
                if (hypot(px - cx, py - cy) <= m.pr + m.soft) continue        // inside its arc, or on its soft edge
                if (abs(px - (m.left + m.pw / 2f)) > m.pw / 2f - 0.5f || abs(py - (m.top + m.ph / 2f)) > m.ph / 2f - 0.5f) continue
                if (!inDisplay(px, py, 1.5f)) continue                            // the display's own corner: check 1
                seen++
                val wo = without ?: m.without().also { without = it }
                val a = b.getPixel(x, y); val o = wo.getPixel(x, y)
                if (abs(Color.red(a) - Color.red(o)) > 10 || abs(Color.green(a) - Color.green(o)) > 10 || abs(Color.blue(a) - Color.blue(o)) > 10) bad++
            }
            if (bad > 3) out += "${m.name}'s $name corner square ($bad of $seen px)"
        }
        return out
    }

    // ---- the cases ----

    /** [cornersOf]: the layers the moving pages' corners are looked at on, where that is not the frame as shown. */
    class Frame(val layers: List<Layer>, val moving: List<Moving>, val cornersOf: List<Layer>? = null)
    class Case(val name: String, val frames: List<Pair<Float, () -> Frame>>)

    private val ts = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
    /** A spring's frames: more of them early, where it passes its place (NoticeThrow.spring). */
    private val springTs = listOf(0f, 0.1f, 0.2f, 0.3f, 0.45f, 0.6f, 0.8f, 1f)

    private fun restLayers(kinds: List<Kind>, dim: Boolean = false): List<Layer> = kinds.filter { k -> !(Kind.PICKER in kinds && k == Kind.MOON) && k != Kind.PICKER }.map { k ->
        if (k == Kind.OTHERS) Layer("another app", z(k), pic(k), 0f, 0f, 0f) else ours(k.name.lowercase(), z(k), pic(k))
    } + (if (Kind.PICKER in kinds) pickerLayers(pickerAtRest()) else emptyList()) +
        (if (dim) listOf(ours("dim", ShellLayer.Z_DIM, flat(Color.BLACK), alpha = 0.58f)) else emptyList())

    // ---- the face picker (feat/face-picker): FacePicker's springs, PickerArt's picture, the face's layer as ShellScene.Shape ----

    private val pickerArt = PickerArt()
    /** The faces in the picker: the live one (index [PICKER_LIVE]) is the moon's picture, the others plain colours of their own. */
    private val PICKER_FACES = 3
    private val PICKER_LIVE = 1
    private val faceColors = intArrayOf(0xFF8A3A5A.toInt(), 0, 0xFF3A8A7A.toInt())
    private fun facePic(i: Int): Bitmap = if (i == PICKER_LIVE) pic(Kind.MOON) else pics.getOrPut("$real/face$i") { flat(faceColors[i]) }
    private val pickerDt = 1.0 / 120.0

    /** A picker run from its opening: [script] at each frame k (a finger, a shut), stepped [frames] frames of 120 Hz. */
    private fun pickerRun(frames: Int, script: (FacePicker, Int) -> Unit = { _, _ -> }): FacePicker {
        val m = FacePicker(PICKER_FACES, PICKER_LIVE)
        for (k in 0 until frames) { script(m, k); m.step(pickerDt) }
        return m
    }

    /** How many frames a run takes until [done] (at most 4 s). */
    private fun pickerFramesUntil(script: (FacePicker, Int) -> Unit = { _, _ -> }, done: (FacePicker) -> Boolean): Int {
        val m = FacePicker(PICKER_FACES, PICKER_LIVE)
        for (k in 0 until 480) { if (done(m)) return k; script(m, k); m.step(pickerDt) }
        return 480
    }

    private val openFrames by lazy { pickerFramesUntil { it.phase == FacePicker.Phase.UP && !it.moving() } }
    private fun pickerAtRest(): FacePicker = pickerRun(openFrames)

    /** The face's layer as [m] places it (null: gone), and the picker's own picture ([skip]: one card's picture left out). */
    private fun faceLayer(m: FacePicker): Layer? {
        val f = m.frame()
        val card = pickerArt.liveCard(m, f) ?: return null
        // The face's layer is the new face's once one is chosen (its exit, and the back after it).
        val face = facePic(if (pickerArt.exitWithNew(m) || m.phase == FacePicker.Phase.GONE && m.changesFace) m.index else PICKER_LIVE)
        val shape = pickerArt.shapeOf(m, f, card, 1f, 1f) ?: return ours("moon", z(Kind.MOON), face)
        return Layer("the face as its card", z(Kind.MOON), face, shape.left(w), shape.top(h), shape.corner, shape.alpha, shape.scale)
    }
    private fun pickerPic(m: FacePicker, skip: Int = -1): Bitmap = blank().also { b ->
        pickerArt.draw(Canvas(b), m, m.frame(), w / FacePicker.W.toFloat(), h / FacePicker.H.toFloat(), { i -> facePic(i) }, skip)
    }
    private fun pickerLayers(m: FacePicker): List<Layer> =
        listOfNotNull(faceLayer(m), if (m.phase == FacePicker.Phase.GONE) null else ours("picker", z(Kind.PICKER), pickerPic(m)))

    /** One frame of the picker over [others] (what else is on the back, at rest): every card's corners looked at. */
    private fun pickerFrame(m: FacePicker, others: List<Kind>, dim: Boolean): Frame {
        val base = restLayers(others.filter { it != Kind.MOON && it != Kind.PICKER }, dim)
        val face = faceLayer(m)
        val gone = m.phase == FacePicker.Phase.GONE
        val pick = if (gone) null else ours("picker", z(Kind.PICKER), pickerPic(m))
        val layers = base + listOfNotNull(face, pick)
        val moving = ArrayList<Moving>()
        // The radius looked for is the card's own (FacePicker: Xiaomi's), not the one the layer was given.
        val faceRadius = pickerArt.liveCard(m, m.frame())?.corner?.toFloat() ?: 0f
        if (face != null && face.scale != 1f) moving += Moving("the face's card", face.x, face.y, w * face.scale, h * face.scale, faceRadius) {
            compose(base + listOfNotNull(pick))
        }
        if (!gone) {
            val f = m.frame()
            val faceCard = pickerArt.liveCard(m, f)?.index
            for (card in f.cards) {
                if (card.index == faceCard && !pickerArt.exitWithNew(m)) continue
                if (card.corner <= 0.0) continue
                moving += Moving("card ${card.index}", card.left.toFloat(), (card.cy - card.height / 2).toFloat(), card.width.toFloat(), card.height.toFloat(), card.corner.toFloat()) {
                    // Without its picture (and, the middle card of a new face, without the face's layer under it).
                    compose(base + listOfNotNull(face?.takeIf { card.index != faceCard }, ours("picker", z(Kind.PICKER), pickerPic(m, skip = card.index))))
                }
            }
        }
        return Frame(layers, moving)
    }

    /** A picker case: [frames] frames of a run, sampled at [ts]. */
    private fun pickerCase(name: String, frames: Int, others: List<Kind>, dim: Boolean, sampleTs: List<Float> = ts, script: (FacePicker, Int) -> Unit = { _, _ -> }): Case =
        Case(name, sampleTs.map { t -> t to { pickerFrame(pickerRun((t * frames).toInt(), script), others, dim) } })

    /** The picker shut at frame [at] of a run: left without choosing, or (after a swipe to the next card) a new face chosen. */
    private fun shutScript(at: Int, chosen: Boolean): (FacePicker, Int) -> Unit = { m, k ->
        if (chosen) {
            if (k == 0) m.grab()
            if (k in 1..20) m.drag(-k * 20.0)
            if (k == 21) m.release(-900.0)
        }
        if (k == at) m.shut(chosen)
    }
    private val shutAt by lazy { pickerFramesUntil(shutScript(Int.MAX_VALUE, true)) { it.phase == FacePicker.Phase.UP && !it.moving() } }
    private fun shutFrames(chosen: Boolean) = pickerFramesUntil(shutScript(shutAt, chosen)) { it.phase == FacePicker.Phase.GONE }

    /** A layer-level page moving: [kind] at ([x], [y]) over [under]. */
    private fun layerMove(under: List<Kind>, kind: Kind, x: Float, y: Float, dim: Boolean): Frame {
        val base = restLayers(under, dim)
        val moving = ours(kind.name.lowercase(), z(kind), pic(kind), x, y)
        val layers = base + moving
        return Frame(layers, listOf(Moving(moving.name, x, y, w, h, r) { compose(base) }))
    }

    private fun pileMove(under: List<Kind>, scene: NoticePages.Scene, progress: Float, dim: Boolean, top: (NoticePages.Scene) -> NoticePages.Scene?): Frame {
        val base = restLayers(under, dim)
        val layers = base + ours("shade", z(Kind.SHADE), pile(scene, progress))
        val drop = -(1f - progress) * h
        val moving = ArrayList<Moving>()
        if (progress < 1f) moving += Moving("the pile", 0f, drop, w, h, r) { compose(base) }
        top(scene)?.let { without ->
            val p = scene.pages.last()
            moving += Moving("the page on top", p.dx * rear.fitX, drop + p.dy * rear.fitY, w, h, r) { compose(base + ours("shade", z(Kind.SHADE), pile(without, progress))) }
        }
        return Frame(layers, moving)
    }

    private fun noteMove(under: List<Kind>, from: Int, to: Int, dir: Int, at: Float, dim: Boolean, kind: Kind = Kind.NOTES): Frame {
        val base = restLayers(under, dim)
        val name = kind.name.lowercase()
        val layers = base + ours(name, z(kind), turn(from, to, dir, at))
        val e = ShellPage.ease(at) * (if (to >= 0) 1f else NoteTurnArt.GIVE_REACH)
        val fromX = -dir * e * w
        val moving = arrayListOf(Moving("note $from", fromX, 0f, w, h, r) { compose(base + ours(name, z(kind), turn(from, to, dir, at, skip = from))) })
        if (to >= 0) moving += Moving("note $to", fromX + dir * w, 0f, w, h, r) { compose(base + ours(name, z(kind), turn(from, to, dir, at, skip = to))) }
        return Frame(layers, if (at > 0f) moving else emptyList())
    }

    private fun where(edge: PageSlide.Edge, inAt: Float, leaving: Boolean): Pair<Float, Float> {
        // The charging page's own slide (PageSlide), driven by its own clock.
        val s = PageSlide()
        if (leaving) { s.inPlace(); s.leaveTo(edge) } else s.comeFrom(edge)
        s.step(1_000L); s.step(1_000L + ((if (leaving) 1f - inAt else inAt) * ShellPage.SLIDE_MS).toLong())
        return s.x(w) to s.y(h)
    }

    /** The cases of one step of the navigation: [before] + [input] gives [step]. */
    private fun cases(before: RearNav.State, input: RearNav.Input, step: RearNav.Step, dim: Boolean): Case? {
        val after = step.state
        val a = step.anim
        val was = stack(before); val now = stack(after)
        val name = "${a.name} ${was.joinToString("+").lowercase().ifEmpty { "dark" }} -> ${now.joinToString("+").lowercase().ifEmpty { "dark" }}" +
            (if (input is RearNav.Input.Stroke) " (stroke ${input.way.name.lowercase()})" else "") + (if (dim) " [dimmed]" else "")
        fun f(t: Float, make: () -> Frame) = t to make
        val frames: List<Pair<Float, () -> Frame>> = when (a) {
            RearNav.Anim.USAGE_IN, RearNav.Anim.USAGE_OUT, RearNav.Anim.NOTES_IN, RearNav.Anim.NOTES_OUT,
            RearNav.Anim.LIVE_IN, RearNav.Anim.LIVE_OUT, RearNav.Anim.ARRIVAL_IN, RearNav.Anim.ARRIVAL_OUT -> {
                val entering = a == RearNav.Anim.USAGE_IN || a == RearNav.Anim.NOTES_IN || a == RearNav.Anim.LIVE_IN || a == RearNav.Anim.ARRIVAL_IN
                val kind = when (a) {
                    RearNav.Anim.USAGE_IN, RearNav.Anim.USAGE_OUT -> Kind.USAGE
                    RearNav.Anim.NOTES_IN, RearNav.Anim.NOTES_OUT -> Kind.NOTES
                    RearNav.Anim.ARRIVAL_IN, RearNav.Anim.ARRIVAL_OUT -> Kind.ARRIVAL
                    RearNav.Anim.LIVE_IN -> liveKind(after.live ?: return null)
                    else -> liveKind(before.live ?: return null)
                }
                // Everything else as the step leaves it, at rest (the composer puts each at its own height).
                val under = now.filter { it != kind }
                ts.map { t ->
                    f(t) {
                        val at = if (entering) t else 1f - t
                        val (x, y) = when {
                            kind == Kind.CHARGE -> {
                                val edge = if (input is RearNav.Input.Stroke && input.way == RearNav.Way.UP) PageSlide.Edge.TOP
                                    else if (input is RearNav.Input.Stroke && input.way == RearNav.Way.DOWN) PageSlide.Edge.BOTTOM else PageSlide.Edge.RIGHT
                                where(edge, at, !entering)
                            }
                            kind == Kind.NOTES || kind == Kind.ARRIVAL -> 0f to ShellPage.yAt(ShellPage.Edge.BOTTOM, at, h)
                            else -> ShellPage.xAt(ShellPage.Edge.RIGHT, at, w) to 0f
                        }
                        layerMove(under, kind, x, y, dim)
                    }
                }
            }
            RearNav.Anim.PICKER_IN -> {
                // The face shrinks into its card, its neighbours come in (FacePicker's enter, to rest).
                val others = now.filter { it != Kind.MOON && it != Kind.PICKER }
                return Case(name, ts.map { t -> f(t) { pickerFrame(pickerRun((t * openFrames).toInt()), others, dim) } })
            }
            RearNav.Anim.PICKER_OUT -> {
                // Its exit: the middle card grows back to the face; chosen, after a swipe to the next card, with the new
                // face's layer under its picture's cross-fade.
                val chosen = (input as? RearNav.Input.PickerShut)?.chosen ?: false
                val others = was.filter { it != Kind.MOON && it != Kind.PICKER }
                val from = shutAt; val total = shutFrames(chosen)
                val nm = name + if (chosen) " (a new face chosen)" else " (left)"
                return Case(nm, ts.map { t -> f(t) { pickerFrame(pickerRun(from + (t * (total - from)).toInt(), shutScript(shutAt, chosen)), others, dim) } })
            }
            RearNav.Anim.POP_IN, RearNav.Anim.POP_OUT -> {
                val under = now.filter { it != Kind.POP }
                ts.map { t ->
                    f(t) {
                        val shown = if (a == RearNav.Anim.POP_IN) ShellNotices.popShown((t * ShellNotices.IN_MS).toLong())
                            else ShellNotices.popShown(ShellNotices.IN_MS + ShellNotices.HOLD_MS + (t * ShellNotices.OUT_MS).toLong())
                        layerMove(under, Kind.POP, 0f, ShellNotices.popY(shown, h), dim)
                    }
                }
            }
            RearNav.Anim.POP_THROWN_LEFT, RearNav.Anim.POP_THROWN_RIGHT -> {
                // THROWN OUT (ShellNotices' banner): it flies on from where the finger left it (a throw is at least
                // NoticeShade.DISMISS_DX), the label rising on its own layer over everything but the display's shape.
                val dir = if (a == RearNav.Anim.POP_THROWN_LEFT) -1f else 1f
                val kind = if (step.does == RearNav.Does.SNOOZE) NoticeThrow.Kind.SNOOZE else NoticeThrow.Kind.DISMISS
                val from = dir * NoticeShade.DISMISS_DX * rear.fitX
                val under = now.filter { it != Kind.POP }
                ts.map { t ->
                    f(t) {
                        val x = ShellNotices.popThrownX(from, dir, t, w)
                        val fr = layerMove(under, Kind.POP, x, 0f, dim)
                        Frame(fr.layers + ours("label", ShellLayer.Z_SHEET, labelPic(kind, (t * NoticeShade.FLY_MS).toLong())), fr.moving)
                    }
                }
            }
            RearNav.Anim.POP_GIVES -> {
                // One that may not be thrown: it went as far as it gives (NoticeThrow.give) and springs back past its place once.
                val dir = if (input is RearNav.Input.Stroke && input.way == RearNav.Way.LEFT) -1f else 1f
                val from = NoticeThrow.give(dir * 1000f) * rear.fitX
                val under = now.filter { it != Kind.POP }
                springTs.map { t -> f(t) { layerMove(under, Kind.POP, ShellNotices.popSpringX(from, t), 0f, dim) } }
            }
            RearNav.Anim.ARRIVAL_OLDER, RearNav.Anim.ARRIVAL_NEWER, RearNav.Anim.ARRIVAL_END_OLDER, RearNav.Anim.ARRIVAL_END_NEWER -> {
                // Along the notes standing at their place: the arrival page turns them as the notes page does (NoteTurnArt).
                if (now.last() != Kind.ARRIVAL) return null
                val under = now.filter { it != Kind.ARRIVAL }
                val dir = if (a == RearNav.Anim.ARRIVAL_OLDER || a == RearNav.Anim.ARRIVAL_END_OLDER) 1 else -1
                val to = if (a == RearNav.Anim.ARRIVAL_OLDER || a == RearNav.Anim.ARRIVAL_NEWER) after.arrival else -1
                ts.map { t -> f(t) { noteMove(under, before.arrival, to, dir, if (to >= 0) t else t * NoteTurnArt.GIVE_PEAK, dim, Kind.ARRIVAL) } }
            }
            RearNav.Anim.SHADE_IN, RearNav.Anim.SHADE_OUT -> {
                val under = now.filter { it != Kind.SHADE }
                ts.map { t -> f(t) { pileMove(under, pileScene(), if (a == RearNav.Anim.SHADE_IN) t else 1f - t, dim) { null } } }
            }
            RearNav.Anim.NOTE_OLDER, RearNav.Anim.NOTE_NEWER, RearNav.Anim.NOTE_END_OLDER, RearNav.Anim.NOTE_END_NEWER -> {
                if (now.last() != Kind.NOTES) return null
                val under = now.filter { it != Kind.NOTES }
                val dir = if (a == RearNav.Anim.NOTE_OLDER || a == RearNav.Anim.NOTE_END_OLDER) 1 else -1
                val to = if (a == RearNav.Anim.NOTE_OLDER || a == RearNav.Anim.NOTE_NEWER) after.pin else -1
                ts.map { t -> f(t) { noteMove(under, before.pin, to, dir, if (to >= 0) t else t * NoteTurnArt.GIVE_PEAK, dim) } }
            }
            else -> return null
        }
        return Case(name, frames)
    }

    private val reachable: List<RearNav.State> by lazy {
        val seen = LinkedHashSet<RearNav.State>(); val todo = ArrayDeque<RearNav.State>()
        seen += RearNav.State(); todo += RearNav.State()
        while (todo.isNotEmpty()) {
            val s = todo.removeFirst()
            for (i in RearNav.INPUTS) { val n = RearNav.next(s, i).state; if (seen.add(n)) todo += n }
        }
        seen.toList()
    }

    /** Every animation the table gives, one case per kind of picture (what is on the back before and after). */
    private fun allCases(): List<Case> {
        val out = LinkedHashMap<String, Case>()
        val anims = HashSet<RearNav.Anim>()
        for (dim in listOf(false, true)) for (s in reachable) for (i in RearNav.INPUTS) {
            val step = RearNav.next(s, i)
            if (step.anim == RearNav.Anim.NONE) continue
            anims += step.anim
            val c = cases(s, i, step, dim) ?: continue
            out.putIfAbsent(c.name, c)
        }
        // Every state at rest (this covers CUT, LIVE_SWAP and NONE: they change at once, and what is left is at rest).
        for (dim in listOf(false, true)) for (s in reachable) {
            val k = stack(s)
            if (k.isEmpty()) continue
            val name = "AT REST ${k.joinToString("+").lowercase()}" + (if (dim) " [dimmed]" else "")
            out.putIfAbsent(name, Case(name, listOf(1f to { Frame(restLayers(k, dim), emptyList()) })))
        }
        // The pile's own strokes (NoticePages): the logos' page up and away (logos first; no notification page goes up
        // alone, a stroke up takes the whole pile, as SHADE_OUT draws it), a page thrown out to either side, and with none waiting.
        for (dim in listOf(false, true)) {
            val sfx = if (dim) " [dimmed]" else ""
            val under = listOf(Kind.MOON, Kind.TIMER)
            fun pileCase(name: String, page: (Float) -> NoticePages.Page) = Case(name + sfx, ts.map { t -> t to {
                val top = page(t)
                val scene = pileScene(top)
                pileMove(under, scene, 1f, dim) { NoticePages.Scene(scene.pages.dropLast(1), scene.line, scene.focus, false) }
            } })
            listOf(
                // As NoticePages.scene has it: the newest under the logos' page, and the line's mark going from one to the other.
                Case("PILE the logos' page up and away, the newest under it (logos first)$sfx", ts.map { t -> t to {
                    val scene = NoticePages.Scene(
                        listOf(NoticePages.Page(items[0], 0f, 0f, 1f), NoticePages.Page(null, 0f, -t * Panel.HEIGHT, 1f)), items, -(1f - t), false,
                    )
                    pileMove(under, scene, 1f, dim) { NoticePages.Scene(scene.pages.dropLast(1), scene.line, scene.focus, false) }
                } }),
                pileCase("PILE the page on top thrown out to the right") { t -> NoticePages.Page(items[0], t * Panel.WIDTH, 0f, 1f) },
                pileCase("PILE the page on top thrown out to the left") { t -> NoticePages.Page(items[0], -t * Panel.WIDTH, 0f, 1f) },
                Case("PILE nothing waiting, coming down$sfx", ts.map { t -> t to { pileMove(listOf(Kind.MOON), pileScene(empty = true), t, dim) { null } } }),
            ).forEach { out[it.name] = it }
            // Thrown out one way or the other (feat/notif-swipe): the page that came up under it with the throw's label
            // rising over it; let go short, the page springs back; one that may not be thrown gives and springs back.
            fun pileSpring(name: String, from: Float) = Case(name + sfx, springTs.map { t -> t to {
                val scene = pileScene(NoticePages.Page(items[0], ShellNotices.popSpringX(from, t), 0f, 1f))
                pileMove(under, scene, 1f, dim) { NoticePages.Scene(scene.pages.dropLast(1), scene.line, scene.focus, false) }
            } })
            for (dir in listOf(-1f, 1f)) {
                val side = if (dir < 0f) "left" else "right"
                val kind = NoticeThrow.kindOfDx(dir)
                out["PILE the page on top thrown out to the $side, with its label$sfx"] = Case("PILE the page on top thrown out to the $side, with its label$sfx", ts.map { t -> t to {
                    val label = NoticeThrow.labelAt(kind, (t * NoticeShade.FLY_MS).toLong())
                    val scene = pileScene(NoticePages.Page(items[0], dir * t * Panel.WIDTH, 0f, 1f), label = label)
                    pileMove(under, scene, 1f, dim) { NoticePages.Scene(scene.pages.dropLast(1), scene.line, scene.focus, false, scene.label) }
                } })
                // The last page thrown out: the pile goes up with it, and its label stands on a layer of its own.
                out["PILE the last page thrown out to the $side, the label on its own layer$sfx"] = Case("PILE the last page thrown out to the $side, the label on its own layer$sfx", ts.map { t -> t to {
                    val scene = NoticePages.Scene(listOf(NoticePages.Page(items[0], dir * t * Panel.WIDTH, 0f, 1f)), listOf(items[0]), 0f, false)
                    val fr = pileMove(under, scene, 1f, dim) { NoticePages.Scene(emptyList(), listOf(items[0]), 0f, false) }
                    Frame(fr.layers + ours("label", ShellLayer.Z_SHEET, labelPic(kind, (t * NoticeShade.FLY_MS).toLong())), fr.moving)
                } })
                listOf(
                    pileSpring("PILE the page on top let go short to the $side, springing back", dir * (NoticeShade.DISMISS_DX - 1f)),
                    pileSpring("PILE a page that may not be thrown, giving to the $side", NoticeThrow.give(dir * 1000f)),
                ).forEach { out[it.name] = it }
                // The pop-up under the finger and let go short: it springs back (a pop-up that may be thrown, not thrown).
                val pop = "POP let go short to the $side, springing back$sfx"
                out[pop] = Case(pop, springTs.map { t -> t to { layerMove(under, Kind.POP, ShellNotices.popSpringX(dir * (NoticeShade.DISMISS_DX - 1f) * rear.fitX, t), 0f, dim) } })
            }
            // The notes put in order (ARRANGE_IN, NOTE_CARRIED, NOTE_SWAP, ARRANGE_OUT; and the give with one note):
            // the carousel's own springs, every 48 ms of a second.
            fun carousel(name: String, act: (NoteCarousel, Int) -> Unit) = Case(name + sfx, run {
                val car = NoteCarousel()
                car.enter(arrangePins.map { it.at }, arrangePins[1].at)
                var now = 1_000L
                (0 until 22).map { k ->
                    act(car, k)
                    now += 48L
                    car.step(now - 32L); car.step(now - 16L); car.step(now)
                    // The frame is drawn now (Robolectric draws at once): kept as a picture, not as the carousel's later state.
                    val fr = carouselFrame(car, dim)
                    val pics = fr.layers; val moving = fr.moving.map { m -> val wo = m.without(); Moving(m.name, m.left, m.top, m.pw, m.ph, m.pr, m.soft, m.only) { wo } }
                    (k / 21f) to { Frame(pics, moving) }
                }
            })
            listOf(
                carousel("ARRANGE the page lifts into the carousel and back") { car, k -> if (k == 12) car.leave() },
                carousel("ARRANGE the held note carried two places along") { car, k ->
                    if (k == 8) car.grab(600f, 0L, w)
                    if (k in 9..14) car.drag(600f + (k - 8) * 80f, 0L)
                    if (k == 15) car.drop(1080f, 0L)
                },
                carousel("ARRANGE two notes swapping places") { car, k -> if (k == 8) car.swapWith(arrangePins[3].at) },
                Case("ARRANGE the give with one note$sfx", run {
                    val car = NoteCarousel(); car.give(arrangePins[0].at)
                    var now = 1_000L
                    (0 until 16).map { k ->
                        now += 48L; car.step(now - 32L); car.step(now - 16L); car.step(now)
                        val fr = carouselFrame(car, dim)
                        val pics = fr.layers; val moving = fr.moving.map { m -> val wo = m.without(); Moving(m.name, m.left, m.top, m.pw, m.ph, m.pr, m.soft, m.only) { wo } }
                        (k / 15f) to { Frame(pics, moving) }
                    }
                }),
            ).forEach { out[it.name] = it }
            // The face picker's cards (feat/face-picker): swiped to the next one and settling; dragged short and back;
            // past the last card the rubber band; a fling. Every card's corners and the face's card's, frame by frame.
            fun swipe(name: String, script: (FacePicker, Int) -> Unit) =
                pickerCase("PICKER $name$sfx", openFrames + 150, emptyList(), dim, (0..12).map { it / 12f }) { m, k -> if (k >= openFrames) script(m, k - openFrames) }
            listOf(
                swipe("the cards dragged to the next and settling") { m, k -> if (k == 0) m.grab(); if (k in 1..40) m.drag(-k * 10.0); if (k == 41) m.release(0.0) },
                swipe("a card dragged short and going back") { m, k -> if (k == 0) m.grab(); if (k in 1..20) m.drag(k * 7.0); if (k == 21) m.release(0.0) },
                swipe("the rubber band past the last card") { m, k ->
                    if (k == 0) { m.grab() }
                    if (k in 1..30) m.drag(-k * 25.0)
                    if (k == 31) m.release(-2000.0)
                    if (k == 80) m.grab()
                    if (k in 81..110) m.drag(-(k - 80) * 25.0)
                    if (k == 111) m.release(0.0)
                },
                swipe("flung to the one before") { m, k -> if (k == 0) m.grab(); if (k in 1..6) m.drag(k * 12.0); if (k == 7) m.release(1800.0) },
            ).forEach { out[it.name] = it }
            // A pinned note arriving on the back (ShellBeam), over the moon, the notes page put under it as it lands.
            val beamTimes = (0..10).map { it * PinBeam.BACK_MS / 10 }
            out["BEAM a pinned note arriving$sfx"] = Case("BEAM a pinned note arriving$sfx", beamTimes.map { ms -> (ms.toFloat() / PinBeam.BACK_MS) to { beamFrame(ms, dim) } })
        }
        assertTrue("every animation the table has is among them: ${RearNav.Anim.values().toSet() - anims}", anims.containsAll(RearNav.Anim.values().toSet() - RearNav.Anim.NONE))
        return out.values.toList()
    }

    /** The pin's card [ms] into its arrival on the back, over the moon (the notes page put under it as it lands). */
    private fun beamFrame(ms: Long, dim: Boolean): Frame {
        val b = PinBeam.back(ms)
        val under = if (ms >= PinBeam.ARRIVE_MS) listOf(Kind.MOON, Kind.NOTES) else listOf(Kind.MOON)
        val base = restLayers(under, dim)
        val layers = base + ours("beam", ShellLayer.Z_WAKE, beam(b))
        val s = b.scale
        val card = abs(b.turn) < 0.5f && s < 0.995f && b.alpha > 0.99f
        // Its rim, a fine line on the card's own rounded edge (PinBeamArt: half of 1.2% of its width, at least 1.2 px), lies half outside it.
        val rim = maxOf(0.012f * w * s * 0.5f, 1.2f) / 2f + 1.5f
        val moving = if (card) listOf(Moving("the pin's card", ShellBeam.cardX(b, w) - w * s / 2f, h / 2f - h * s / 2f, w * s, h * s, r * s, rim) {
            compose(base + ours("beam", ShellLayer.Z_WAKE, beam(b.copy(alpha = 0f, glow = 0f))))
        }) else emptyList()
        // ITS LIT RIM is light round the card on purpose (a round ring, wider and fainter): the card's own corners are
        // looked at without it, and the frame as it is shown against the display's corners.
        return Frame(layers, moving, cornersOf = if (card) base + ours("beam", ShellLayer.Z_WAKE, beam(b.copy(glow = 0f))) else null)
    }

    private fun check(case: Case): List<String> {
        val out = ArrayList<String>()
        for ((t, make) in case.frames) {
            val fr = make()
            val b = compose(fr.layers)
            val forCorners = fr.cornersOf?.let { compose(it) } ?: b
            val problems = outsideDisplay(b) + fr.moving.flatMap { squareCorners(forCorners, it) }
            if (problems.isNotEmpty()) out += "${case.name} at ${(t * 100).toInt()}%: ${problems.joinToString("; ")}"
            freeFrame()
        }
        return out
    }

    @Test fun `every corner of everything on the back is round, in every animation the navigation has and at rest`() {
        val cases = allCases()
        val failed = ArrayList<String>()
        val casesFailed = LinkedHashSet<String>()
        for (c in cases) { val p = check(c); if (p.isNotEmpty()) { failed += p; casesFailed += c.name } }
        val dir = File("build/corners").apply { mkdirs() }
        File(dir, "report-${version()}.txt").writeText(
            "${cases.size} cases, ${cases.sumOf { it.frames.size }} frames; ${casesFailed.size} cases fail\n\n" +
                "CASES THAT FAIL:\n" + casesFailed.joinToString("\n") + "\n\nEVERY FAILING FRAME:\n" + failed.joinToString("\n") + "\n",
        )
        println("RearCorners: ${cases.size} cases, ${casesFailed.size} fail")
        assertTrue("${casesFailed.size} of ${cases.size} cases have a square corner:\n" + casesFailed.take(60).joinToString("\n") +
            "\n…\nfirst frames:\n" + failed.take(12).joinToString("\n"), casesFailed.isEmpty())
    }

    // ---- what else stands on the back: the screenshot's polaroid, an app on the back ----

    /**
     * THE SCREENSHOT'S POLAROID (ShotPolaroid, at ShellLayer.Z_FRAME, over everything but the display's shape): the back
     * as taken (screencap, so with the display's shape in it: black outside its corners) shrinks into a card. At every
     * moment, from the flash to its leaving, binned or not: (1) nothing outside the display; (2) the card's corners are
     * round (outside its rounded edge, the back without it shows); (3) no black of the shot's own corners shows inside
     * the card. Before: the paper's corner 6 px at rest (0 as it began), the picture square, and the shot's black corners
     * wedges inside the frame.
     */
    @Test fun `the screenshot's polaroid has round corners and no black wedges, at every moment`() {
        val under = flat(0xFF4A5A78.toInt())
        val shot = flat(0xFFD9A441.toInt()).also { mask?.invoke(RearShape, Canvas(it), w, h, r) ?: PageCorners.cut(Canvas(it), w, h, r) }
        val times = listOf(0L, 40L, 80L, 120L, 200L, 300L, 450L, 620L, 1_500L, 4_700L, 4_800L, 4_900L, 5_000L)
        val failed = ArrayList<String>()
        val dir = File("build/corners/polaroid").apply { mkdirs() }
        for (binned in listOf(0f, 0.3f, 0.6f)) for (t in times) {
            if (binned > 0f && t < 620L) continue
            val p = ShotPolaroid(0, rear) { null }
            val card = blank().also { p.frame(Canvas(it), shot, t, binned, cardOnly = true) }
            val k = p.cardAt(t, binned)
            val b = compose(listOf(Layer("moon", ShellLayer.Z_SCENE, under, 0f, 0f, 0f), ours("polaroid", ShellLayer.Z_FRAME, card)))
            val problems = ArrayList<String>()
            problems += outsideDisplay(b)
            // The card's outline: the paper once it shows, the picture alone before.
            val paper = k.shrink > 0f && k.alpha > 0f
            val ow = if (paper) k.cardW else k.picW; val oh = if (paper) k.cardH else k.picH
            val or = if (paper) k.paperR else k.photoR
            val ox = if (paper) 0f else k.side; val oy = if (paper) 0f else k.side
            val rad = Math.toRadians(-k.angle.toDouble()); val cos = Math.cos(rad).toFloat(); val sin = Math.sin(rad).toFloat()
            var square = 0; var wedge = 0
            val all = IntArray(W * H).also { b.getPixels(it, 0, W, 0, 0, W, H) }
            val half = hypot(k.cardW, k.cardH) / 2f + 2f
            val x0 = (k.cx - half).toInt().coerceIn(0, W); val x1 = (k.cx + half).toInt().coerceIn(0, W)
            val y0 = (k.cy - half).toInt().coerceIn(0, H); val y1 = (k.cy + half).toInt().coerceIn(0, H)
            for (y in y0 until y1) for (x in x0 until x1) {
                // Into the card's own frame: its top-left at (0, 0), unturned.
                val dx = x + 0.5f - k.cx; val dy = y + 0.5f - k.cy
                val u = dx * cos - dy * sin + k.cardW / 2f; val v = dx * sin + dy * cos + k.cardH / 2f
                if (!inDisplay(x + 0.5f, y + 0.5f, 1.5f)) continue
                val px = all[y * W + x]
                if (u > ox + 0.5f && v > oy + 0.5f && u < ox + ow - 0.5f && v < oy + oh - 0.5f && outsideRound(u - ox, v - oy, ow, oh, or, 1.5f)) {
                    val o = under.getPixel(x, y)
                    if (abs(Color.red(px) - Color.red(o)) > 10 || abs(Color.green(px) - Color.green(o)) > 10 || abs(Color.blue(px) - Color.blue(o)) > 10) square++
                }
                // Anywhere on the card, 2 px in from its rounded edge: never the shot's black corners (the picture, the paper,
                // the back under a paper not yet opaque: none of them is near black).
                val cu = u - ox; val cv = v - oy
                if (k.alpha > 0.5f && ow > 8f && cu > 2f && cv > 2f && cu < ow - 2f && cv < oh - 2f && !outsideRound(cu, cv, ow, oh, or, -2f) &&
                    max(Color.red(px), max(Color.green(px), Color.blue(px))) < 40) wedge++
            }
            if (square > 3) problems += "the card's corners square ($square px)"
            if (wedge > 3) problems += "black of the shot's corners inside the card ($wedge px)"
            if (problems.isNotEmpty()) {
                failed += "polaroid at $t ms${if (binned > 0f) ", binned $binned" else ""}: ${problems.joinToString("; ")}"
                File(dir, "fail-$t-$binned.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        assertTrue("the screenshot's polaroid:\n" + failed.joinToString("\n"), failed.isEmpty())
    }

    /** Whether ([u], [v]) in a [pw] x [ph] box is outside its corners rounded by [pr], by more than [soft] (in a corner's square only). */
    private fun outsideRound(u: Float, v: Float, pw: Float, ph: Float, pr: Float, soft: Float): Boolean {
        val cx = if (u < pr) pr else if (u > pw - pr) pw - pr else return false
        val cy = if (v < pr) pr else if (v > ph - pr) ph - pr else return false
        return hypot(u - cx, v - cy) > pr + soft
    }

    /**
     * AN APP ON THE BACK (PrivateDisplay at Z_APP, no radius of its own; the camera strip at Z_STRIP round it,
     * StripColor.draw): at every width the app may have (GuestWidth: the narrowest, between, the whole), filling its
     * display or letterboxed in it, over the whole back (a wide one), with and without a keyboard: every corner of what
     * shows of it is round, in the strip's colour, the left ones beside the lenses too. Before: square wherever its box
     * stood inside the display (all four for any width under the whole, the two left ones at x 296 at the whole).
     */
    @Test fun `an app on the back has round corners at every width, letterboxed or not, with a keyboard or not`() {
        val stripColor = 0xFFC04040.toInt(); val appColor = 0xFF3080C0.toInt(); val keyColor = 0xFF606060.toInt()
        val failed = ArrayList<String>()
        val dir = File("build/corners/guest").apply { mkdirs() }
        class G(val name: String, val area: GuestShape.Bounds, val app: GuestShape.Bounds)
        val guests = ArrayList<G>()
        for (wd in listOf(GuestWidth.min(rear), 480, 600, null)) {
            val area = GuestWidth.area(rear, wd)
            guests += G("width ${GuestWidth.label(rear, wd)}", area, area)
            // Letterboxed in its display: a portrait app, centred, with bars.
            val inner = minOf(area.width - 40, 320)
            val l = area.left + (area.width - inner) / 2
            guests += G("width ${GuestWidth.label(rear, wd)}, letterboxed", area, GuestShape.Bounds(l, 30, l + inner, H - 30))
        }
        val whole = GuestShape.full(rear)
        guests += G("over the whole back", whole, whole)
        guests += G("over the whole back, letterboxed right of the lenses", whole, GuestShape.Bounds(rear.cutoutPx + 40, 0, W - 40, H))
        for (g in guests) for (kb in listOf<Int?>(null, 380)) {
            // The app's display as its layer has it: the app, black bars round it, the keyboard its whole width.
            val shown = Bitmap.createBitmap(g.area.width, g.area.height, Bitmap.Config.ARGB_8888).also {
                val c = Canvas(it); c.drawColor(Color.BLACK)
                c.drawRect((g.app.left - g.area.left).toFloat(), g.app.top.toFloat(), (g.app.right - g.area.left).toFloat(), g.app.bottom.toFloat(), Paint().apply { color = appColor })
                if (kb != null) c.drawRect(0f, kb.toFloat(), g.area.width.toFloat(), g.area.height.toFloat(), Paint().apply { color = keyColor })
            }
            val strip = blank().also { StripColor.draw(Canvas(it), rear, g.app, g.area, kb, stripColor) }
            val layers = listOf(
                // Under it what the back had under a guest (our pages step away while a guest has the back): lit, to be seen.
                Layer("under", Int.MIN_VALUE, flat(0xFF4A5A78.toInt()), 0f, 0f, 0f),
                Layer("app", ShellLayer.Z_APP, shown, g.area.left.toFloat(), 0f, 0f),
                Layer("strip", ShellLayer.Z_STRIP, strip, 0f, 0f, 0f),
            )
            val b = compose(layers)
            val inStrip = { flat(stripColor) }
            val problems = ArrayList<String>(outsideDisplay(b))
            val a = g.app
            val appBottom = if (kb != null) minOf(a.bottom, kb) else a.bottom
            if (appBottom > a.top) {
                val bh = (appBottom - a.top).toFloat()
                val keyOver = kb != null && kb < a.bottom
                val pr = minOf(r, a.width / 2f, if (keyOver) bh else bh / 2f)
                problems += squareCorners(b, Moving("the app", a.left.toFloat(), a.top.toFloat(), a.width.toFloat(), bh, pr,
                    only = if (keyOver) setOf(0, 1) else setOf(0, 1, 2, 3), without = inStrip))
            }
            if (kb != null) {
                // As far as it shows: the lens column is the strip's.
                val kl = maxOf(g.area.left, minOf(rear.cutoutPx, a.left)); val kw = (g.area.right - kl).toFloat()
                val bh = (g.area.bottom - kb).toFloat()
                problems += squareCorners(b, Moving("the keyboard", kl.toFloat(), kb.toFloat(), kw, bh,
                    minOf(r, kw / 2f, bh), only = setOf(2, 3), without = inStrip))
            }
            // The frame round it (PortalFrame) runs on the same rounded edge.
            val frame = PortalFrame.shapesFor(rear, a).single() as ElectricBorder.Shape.Rounded
            if (frame.r != frame.leftR || frame.r < minOf(r, a.width / 2f, a.height / 2f) - 0.01f) problems += "the frame round it is not round at every corner ($frame)"
            if (problems.isNotEmpty()) {
                val name = "${g.name}${if (kb != null) ", a keyboard from y $kb" else ""}"
                failed += "$name: ${problems.joinToString("; ")}"
                File(dir, "fail-${failed.size}.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        assertTrue("an app on the back:\n" + failed.joinToString("\n"), failed.isEmpty())
    }

    // ---- the sheet: the worst cases, as the pages' own painters draw them ----

    private fun version() = if (mask == null) "before" else "after"

    private val sheetCases: List<Pair<String, (Float) -> Frame>> = listOf(
        "the notes page coming up over the moon" to { t -> layerMove(listOf(Kind.MOON), Kind.NOTES, 0f, ShellPage.yAt(ShellPage.Edge.BOTTOM, t, h), false) },
        "the notifications' pile coming down over the moon" to { t -> pileMove(listOf(Kind.MOON), pileScene(), t, false) { null } },
        "a notification thrown out to the right" to { t -> pileMove(listOf(Kind.MOON), pileScene(NoticePages.Page(items[0], t * Panel.WIDTH * 0.6f, 0f, 1f)), 1f, false) { null } },
        "the pop-up coming down over the timer page" to { t -> layerMove(listOf(Kind.MOON, Kind.TIMER), Kind.POP, 0f, ShellNotices.popY(ShellNotices.popShown((t * ShellNotices.IN_MS).toLong()), h), false) },
        "the charging page sent away up" to { t -> val (x, y) = where(PageSlide.Edge.TOP, 1f - t, true); layerMove(listOf(Kind.MOON), Kind.CHARGE, x, y, false) },
        "one note turning to the next" to { t -> noteMove(listOf(Kind.MOON), 0, 1, 1, t, false) },
        "a pinned note arriving (30% to 60% of its time)" to { t -> beamFrame((PinBeam.BACK_MS * (0.2f + 0.4f * t)).toLong(), false) },
    )

    @Test fun `the worst cases, drawn for a look`() {
        real = true
        val dir = File("build/corners/frames/${version()}").apply { mkdirs() }
        val sheetTs = listOf(0.25f, 0.5f, 0.75f, 1f)
        for ((k, case) in sheetCases.withIndex()) {
            val strip = Bitmap.createBitmap(W / 2 * sheetTs.size + 8 * (sheetTs.size - 1), H / 2, Bitmap.Config.ARGB_8888)
            val c = Canvas(strip)
            c.drawColor(0xFF202020.toInt())
            for ((i, t) in sheetTs.withIndex()) {
                val b = compose(case.second(t).layers)
                c.save(); c.translate(i * (W / 2 + 8f), 0f); c.scale(0.5f, 0.5f); c.drawBitmap(b, 0f, 0f, null); c.restore()
            }
            File(dir, "$k.png").outputStream().use { strip.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        real = false
        // Both versions there: one sheet, each case before over after.
        val before = File("build/corners/frames/before"); val after = File("build/corners/frames/after")
        if (!before.isDirectory || !after.isDirectory) return
        val strips = sheetCases.indices.map { k -> BitmapFactory.decodeFile(File(before, "$k.png").path) to BitmapFactory.decodeFile(File(after, "$k.png").path) }
        if (strips.any { it.first == null || it.second == null }) return
        val sw = strips[0].first.width; val sh = strips[0].first.height
        val head = 46; val gap = 14; val label = 150
        val sheet = Bitmap.createBitmap(label + sw + 20, strips.size * (head + 2 * sh + gap + 10) + 20, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet)
        c.drawColor(0xFF2A2A2A.toInt())
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 26f }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFBBBBBB.toInt(); textSize = 22f }
        var y = 20f
        for ((k, pair) in strips.withIndex()) {
            c.drawText("${sheetCases[k].first}  (25%, 50%, 75%, 100% of what is shown)", label.toFloat(), y + 30f, text)
            y += head
            c.drawText("before", 20f, y + sh / 2f, small); c.drawBitmap(pair.first, label.toFloat(), y, null); y += sh + 10
            c.drawText("after", 20f, y + sh / 2f, small); c.drawBitmap(pair.second, label.toFloat(), y, null); y += sh + gap
        }
        File("build/corners/contact-sheet.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
