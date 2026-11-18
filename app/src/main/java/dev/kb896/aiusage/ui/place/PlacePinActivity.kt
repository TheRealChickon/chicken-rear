package dev.kb896.aiusage.ui.place

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dev.kb896.aiusage.data.ConfigStore
import dev.kb896.aiusage.place.Arrivals
import dev.kb896.aiusage.place.Fixes
import dev.kb896.aiusage.place.PinList
import dev.kb896.aiusage.place.PlacePermission
import dev.kb896.aiusage.place.PlaceSearch
import dev.kb896.aiusage.place.PlaceStore
import dev.kb896.aiusage.place.Places
import dev.kb896.aiusage.place.Prompt
import dev.kb896.aiusage.place.Tiles
import dev.kb896.aiusage.ui.PinActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlacePinActivity : ComponentActivity() {
    enum class Mode { PIN, TILE, NEW_TILE, MOVE_PIN }
    enum class Phase { OPENING, CHOOSING, LEAVING, CLOSING }

    internal lateinit var store: PlaceStore
    internal var mode = Mode.PIN
    internal var tileId: Long? = null
    internal var pinAt: Long = 0L

    // What the sheet shows, as state the Compose sheet reads.
    internal var text by mutableStateOf("")
    /**
     * A PICTURE shared to "Pin to location" (ui/PinShare): read once as the prompt opens, kept as a file of ours
     * (data/PinImageFiles, its name in [image] and in the draft), shown small where his words are; [text] is then its
     * caption. The note made is a picture's note for that place.
     */
    internal var picture by mutableStateOf<android.graphics.Bitmap?>(null)
    internal var image: String? = null
    /** The shared picture is being read (a moment): nothing is pinned meanwhile. */
    internal var reading by mutableStateOf(false)
    internal var title by mutableStateOf<String?>(null)
    internal var radius by mutableStateOf(Arrivals.ZONE_DEFAULT)
    internal var phase by mutableStateOf(Phase.OPENING)
    internal var map by mutableStateOf<PlaceMap?>(null)
    internal var mapShown by mutableStateOf(false)
    internal var moving by mutableStateOf(false)
    internal var ringPx by mutableStateOf(0f)
    internal var tiles by mutableStateOf(emptyList<dev.kb896.aiusage.place.Tile>())
    internal var settingTile by mutableStateOf<dev.kb896.aiusage.place.Tile?>(null)
    internal var query by mutableStateOf("")
    internal var hits by mutableStateOf(emptyList<PlaceSearch.Hit>())
    internal var searchNote by mutableStateOf<String?>(null)
    internal var need by mutableStateOf(PlacePermission.Need.NOTHING)
    internal var accuracyOff by mutableStateOf(false)
    internal var shakes by mutableStateOf(0)
    internal var hereBusy by mutableStateOf(false)
    internal var naming by mutableStateOf(false)
    internal var placeName by mutableStateOf<String?>(null)
    /** A place is chosen: the map was moved, a result or "Here" taken, or it opened on a place (draft, tile, note). Not the world view of a first opening. */
    internal var positioned by mutableStateOf(false)
    internal var blurOn = true

    /** Where a search result was, for its name (Prompt.name). */
    internal var namedLat: Double? = null
    internal var namedLng: Double? = null
    /** Where the map stands before it is made, and whenever it is not (offline, its first moment). */
    internal var lat = 20.0
    internal var lng = 0.0
    internal var zoom = 1.5

    private val searchGate = PlaceSearch.Gate()
    private var searchJob: Job? = null
    private var fineAsked = 0
    private var bgAsked = 0

    private val askFine = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { fineAsked++; readNeed() }
    private val askBackground = registerForActivityResult(ActivityResultContracts.RequestPermission()) { bgAsked++; readNeed() }
    private val askAccuracy = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { readNeed() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        store = PlaceStore(applicationContext)
        val debug = dev.kb896.aiusage.DebugExtras.of(this, intent, DEBUG_EXTRAS)
        // A debug build: --ez arrive_demo true plays everything after an arrival is noticed, made up: the chime by its
        // rules and the back's page (pop-demo "arrive", marked "Demo"); nothing is stored, no position is read.
        if (debug.bool("arrive_demo")) {
            val words = PinActivity.textOf(intent)?.replace('|', ' ')?.replace('\n', ' ') ?: "A note for this place"
            runCatching { java.io.File(getExternalFilesDir(null), "pop-demo").writeText("arrive|${(debug.string("place") ?: "Gym").replace('|', ' ')}|$words") }
            dev.kb896.aiusage.place.ArrivalChime.demo(this)
            finish(); overridePendingTransition(0, 0)
            return
        }
        // A debug build: --ez fence_test true is THE GATE of SPEC step 2 without going anywhere: a note "… (Test)" where the
        // phone is now (one fix, his grant needed), its place taken as one he is outside of, and its fence set with an
        // initial entry, so Play services itself says ENTER, the receiver runs, and the note stands on the back with the
        // chime, whatever the screen does meanwhile. Its bin takes it away again.
        if (debug.bool("fence_test")) {
            val words = (PinActivity.textOf(intent) ?: "Fence test") + " (Test)"
            if (dev.kb896.aiusage.place.Fences.possible(this)) Fixes.one(this) { fix ->
                if (fix == null) { Log.i(TAG, "fence test: no fix"); return@one }
                val place = Arrivals.Place(fix.lat, fix.lng, Arrivals.ZONE_DEFAULT)
                Places.write(this, "a fence test") { c -> c.copy(pins = PinList.added(c.pins, words, System.currentTimeMillis(), PinList.Where(place.lat, place.lng, place.radius, null)).pins) }
                store.update { it.copy(visits = it.visits + (place.key to Arrivals.Visit(where = Arrivals.Where.OUTSIDE, owed = true))) }
                dev.kb896.aiusage.place.Fences.set(applicationContext, listOf(place to Arrivals.Initial.ENTER)) { ok -> Log.i(TAG, "fence test: ${if (ok.isEmpty()) "not set" else "set with an initial entry — Play services' ENTER should follow"}") }
            } else Log.i(TAG, "fence test: precise, all the time and Location are needed first")
            finish(); overridePendingTransition(0, 0)
            return
        }
        val s = store.load()
        tiles = s.tiles
        s.camera?.let { lat = it.lat; lng = it.lng; zoom = it.zoom; positioned = true }
        tileId = intent.getLongExtra(EXTRA_TILE, 0L).takeIf { it != 0L }
        pinAt = intent.getLongExtra(EXTRA_PIN_AT, 0L)
        val newTile = intent.getStringExtra(EXTRA_NEW_TILE)?.trim()?.takeIf { it.isNotEmpty() }
        mode = when { tileId != null -> Mode.TILE; newTile != null -> Mode.NEW_TILE; pinAt != 0L -> Mode.MOVE_PIN; else -> Mode.PIN }
        val incoming = PinActivity.textOf(intent)?.trim()?.take(PinList.LONGEST)
        when (mode) {
            Mode.PIN -> {
                // A picture shared: one (a note for a place is one note); its words, if any, its caption.
                val shared = (dev.kb896.aiusage.ui.PinShare.of(intent) as? dev.kb896.aiusage.ui.PinShare.Pictures)?.uris?.firstOrNull()
                // The draft comes back when this is the same pin (after the permission page, or the process ended there);
                // a new picture is never an old draft.
                val draft = s.draft?.takeIf { savedInstanceState != null || (shared == null && it.image == null && (incoming == null || it.text == incoming)) }
                // A draft's picture that is not taken up again has no use: its file goes (no note has it; the sweep would take it later).
                s.draft?.image?.takeIf { draft == null }?.let { old -> forgetPicture(old) }
                text = incoming ?: draft?.text.orEmpty()
                image = draft?.image
                when {
                    image != null -> { val name = image; lifecycleScope.launch { picture = withContext(Dispatchers.IO) { dev.kb896.aiusage.data.PinImageFiles.read(applicationContext, name) } } }
                    shared != null -> readPicture(shared)
                }
                if (text.isBlank() && image == null && shared == null) { finish(); overridePendingTransition(0, 0); return }
                draft?.let { d -> if (d.lat != null && d.lng != null) { lat = d.lat; lng = d.lng; zoom = maxOf(zoom, 14.0); positioned = true }; radius = Arrivals.zone(d.radius); placeName = d.name; if (d.name != null) { namedLat = d.lat; namedLng = d.lng }; settingTile = d.tile?.let { id -> tiles.firstOrNull { it.id == id } } }
            }
            Mode.TILE -> {
                val t = tiles.firstOrNull { it.id == tileId } ?: run { finish(); return }
                title = t.name; text = t.name; radius = t.radius
                if (t.set) { lat = t.lat!!; lng = t.lng!!; zoom = maxOf(zoom, 14.0); positioned = true }
            }
            Mode.NEW_TILE -> { title = newTile; text = newTile!! }
            Mode.MOVE_PIN -> {
                val p = ConfigStore(applicationContext).load().pins.firstOrNull { it.at == pinAt && it.placed } ?: run { finish(); return }
                text = p.text; radius = p.zone; placeName = p.place; lat = p.lat!!; lng = p.lng!!; zoom = maxOf(zoom, 14.0); positioned = true
                // A picture's note moved: its picture where the words are, as it was pinned.
                p.image?.let { name -> image = name; lifecycleScope.launch { picture = withContext(Dispatchers.IO) { dev.kb896.aiusage.data.PinImageFiles.read(applicationContext, name) } } }
                namedLat = p.lat; namedLng = p.lng
            }
        }
        window.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
            statusBarColor = 0; navigationBarColor = 0
            setDecorFitsSystemWindows(false)
            // The keyboard pushes the sheet up (it sits on the keyboard while he types).
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        blurOn = runCatching { windowManager.isCrossWindowBlurEnabled }.getOrDefault(false)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = cancel() })
        readNeed()
        // A ComposeView (the app has no activity-compose): ComponentActivity's setContentView gives it its owners.
        setContentView(androidx.compose.ui.platform.ComposeView(this).apply { setContent { PlaceSheet(this@PlacePinActivity) } })
        Log.i(TAG, "prompt: opened (${mode.name.lowercase()})")
    }

    // -- a shared picture: read once, off the main thread; the address is not kept ------------------------------------

    private fun readPicture(uri: android.net.Uri) {
        reading = true
        lifecycleScope.launch {
            // The prompt gone while it is read (closed, or its screen destroyed): the file the read made goes with it.
            val made = dev.kb896.aiusage.data.PinImageFiles.readOrDrop(Dispatchers.IO,
                { dev.kb896.aiusage.data.PinImageFiles.import(applicationContext, uri, System.currentTimeMillis()) }) { forgetPicture(it.name) }
            reading = false
            if (made == null) {
                // Not readable: with words it is a note of words; with none, nothing to pin.
                if (text.isBlank()) { android.widget.Toast.makeText(this@PlacePinActivity, "The picture could not be read", android.widget.Toast.LENGTH_SHORT).show(); cancel() }
                return@launch
            }
            image = made.name; picture = made.picture
            if (mode == Mode.PIN && phase != Phase.CLOSING && phase != Phase.LEAVING) store.update { it.copy(draft = draft()) }
            else if (phase == Phase.CLOSING) forgetPicture(made.name)
        }
    }

    /** A picture of the prompt's that no note took: its file goes (never one a note has). */
    private fun forgetPicture(name: String) {
        if (ConfigStore(applicationContext).load().pins.any { it.image == name }) return
        dev.kb896.aiusage.data.PinImageFiles.forget(applicationContext, listOf(name))
    }

    // -- the window's blur, as the pin's first beat: up to 100 over the opening, held to the end ---------------------

    internal fun blur(radiusPx: Int) { if (blurOn) runCatching { window.setBackgroundBlurRadius(radiusPx) } }

    // -- the map is made only once the sheet has settled (the opening must not lose a frame to it) ------------------

    internal fun makeMap() {
        if (map != null || isFinishing) return
        val dark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val began = android.os.SystemClock.uptimeMillis()
        val m = runCatching { PlaceMap(this, lat, lng, zoom, dark) }.onFailure { Log.w(TAG, "prompt: no map (${it.javaClass.simpleName}) — the tiles and the button still work") }.getOrNull() ?: return
        m.onMoveStarted = { moving = true; positioned = true }
        m.onMove = { ringPx = m.radiusPx(radius) }
        m.onIdle = { moving = false; ringPx = m.radiusPx(radius); camera() }
        m.onShown = { mapShown = true; Log.i(TAG, "prompt: the map's first whole picture ${android.os.SystemClock.uptimeMillis() - began} ms after it was made") }
        lifecycle.currentState.let { if (it.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) m.view.onStart(); if (it.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) m.view.onResume() }
        map = m
    }

    /** The map's middle now (or where it was to be, before it is made). */
    internal fun here(): Triple<Double, Double, Double> = Triple(map?.lat ?: lat, map?.lng ?: lng, map?.zoom ?: zoom)

    /** Where the map stands, kept: the prompt opens there next time; the draft follows. */
    private fun camera() {
        val (a, b, z) = here()
        lat = a; lng = b; zoom = z
        store.update { it.copy(camera = PlaceStore.Camera(a, b, z), draft = if (mode == Mode.PIN) draft() else it.draft) }
    }

    private fun draft(): PlaceStore.Draft {
        val (a, b, _) = here()
        return PlaceStore.Draft(text, a, b, radius, Prompt.name(placeName, namedLat, namedLng, a, b, radius), settingTile?.id, image)
    }

    internal fun zone(step: Int, done: Boolean) {
        radius = Prompt.zoneAt(step)
        ringPx = map?.radiusPx(radius) ?: ringPx
        if (done) {
            // Let go: the map zooms so the ring is its resting size again.
            map?.let { m -> m.zoom?.let { z -> m.zoomTo(Prompt.zoomFor(z, m.radiusPx(radius), resources.displayMetrics.density)) } }
            if (mode == Mode.PIN) store.update { it.copy(draft = draft()) }
        }
    }

    // -- the permission and Location, as they really are, read on opening and every return ----------------------------

    internal fun readNeed() {
        need = PlacePermission.need(this)
        if (need != PlacePermission.Need.NOTHING) { accuracyOff = false; return }
        // Google's own accuracy switch: off, Play services refuses every fence.
        val req = com.google.android.gms.location.LocationSettingsRequest.Builder()
            .addLocationRequest(com.google.android.gms.location.LocationRequest.Builder(com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY, 60_000L).build()).build()
        runCatching {
            com.google.android.gms.location.LocationServices.getSettingsClient(this).checkLocationSettings(req)
                .addOnSuccessListener { accuracyOff = false }
                .addOnFailureListener { e -> accuracyOff = e is com.google.android.gms.common.api.ResolvableApiException; if (accuracyOff) pendingFix = e as com.google.android.gms.common.api.ResolvableApiException }
        }
    }
    private var pendingFix: com.google.android.gms.common.api.ResolvableApiException? = null

    /** The button's word, as what it does now is. */
    internal fun word(): String = when {
        need != PlacePermission.Need.NOTHING && mode == Mode.PIN -> PlacePermission.word(need)
        accuracyOff && mode == Mode.PIN -> PlacePermission.ACCURACY_WORD
        mode != Mode.PIN -> "Save"
        settingTile != null -> "Save ${settingTile!!.name} and pin"
        else -> "Pin"
    }

    /** The one button: the next step of the permission his to give, or the pin. */
    internal fun button() {
        if (phase != Phase.CHOOSING && phase != Phase.OPENING) return
        if (mode != Mode.PIN) { save(); return }
        when (need) {
            PlacePermission.Need.PRECISE -> askPrecise()
            PlacePermission.Need.ALWAYS -> askAlways()
            PlacePermission.Need.LOCATION_OFF -> runCatching { startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
            PlacePermission.Need.NOTHING -> if (accuracyOff) pendingFix?.let { runCatching { askAccuracy.launch(IntentSenderRequest.Builder(it.resolution).build()) } } ?: pin() else pin()
        }
    }

    /** Precise, by the system's question; refused for good, the app's own page, where it is his to change. */
    internal fun askPrecise() {
        val forGood = fineAsked > 0 && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
        if (forGood) openAppPage() else askFine.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    /** "All the time": Android gives it only on the app's location page, which this request opens. */
    private fun askAlways() {
        val forGood = bgAsked > 0 && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        if (forGood) openAppPage() else askBackground.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    private fun openAppPage() = runCatching {
        startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null)))
    }

    /** "Here": ONE fresh position, never an old one; none in 8 s, a small shake and nothing else. */
    internal fun goHere() {
        if (!PlacePermission.fine(this)) { askPrecise(); return }
        if (hereBusy) return
        hereBusy = true
        Fixes.one(this) { fix ->
            hereBusy = false
            if (fix == null) { shakes++; return@one }
            positioned = true
            map?.fly(fix.lat, fix.lng, maxOf(map?.zoom ?: 15.0, 15.0)) ?: run { lat = fix.lat; lng = fix.lng; zoom = 15.0 }
        }
    }

    // -- search: from the third character, after a pause, one a second, never offline --------------------------------

    internal fun search(q: String) {
        query = q
        searchJob?.cancel()
        hits = emptyList()
        searchNote = null
        if (q.trim().length < PlaceSearch.LEAST) return
        searchJob = lifecycleScope.launch {
            delay(PlaceSearch.QUIET_MS)
            delay(searchGate.waitMs(android.os.SystemClock.uptimeMillis()))
            if (!Fixes.online(this@PlacePinActivity)) { searchNote = "No connection"; return@launch }
            val (a, b, z) = here()
            val url = PlaceSearch.url(q, a.takeIf { map != null || store.load().camera != null }, b.takeIf { map != null || store.load().camera != null }, z, resources.configuration.locales[0].language) ?: return@launch
            searchGate.sent(android.os.SystemClock.uptimeMillis())
            val found = withContext(Dispatchers.IO) {
                runCatching {
                    client.newCall(okhttp3.Request.Builder().url(url).header("User-Agent", PlaceSearch.userAgent(dev.kb896.aiusage.BuildConfig.VERSION_NAME)).build()).execute().use { r ->
                        if (!r.isSuccessful) null else PlaceSearch.parse(r.body?.string().orEmpty())
                    }
                }.getOrNull()
            }
            if (query != q) return@launch
            hits = found.orEmpty()
            searchNote = when { found == null -> "Search did not answer"; found.isEmpty() -> "Nothing found"; else -> null }
        }
    }

    /** A result tapped: the map flies there and its name is kept while he stays within the zone. */
    internal fun choose(hit: PlaceSearch.Hit) {
        placeName = hit.name; namedLat = hit.lat; namedLng = hit.lng; positioned = true
        query = ""; hits = emptyList(); searchNote = null
        map?.fly(hit.lat, hit.lng, 16.0) ?: run { lat = hit.lat; lng = hit.lng; zoom = 16.0 }
        hideKeyboard()
    }

    internal fun hideKeyboard() {
        runCatching { getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(window.decorView.windowToken, 0) }
    }

    // -- tiles ---------------------------------------------------------------------------------------------------------

    /** A tile tapped: one with a place pins there at once; a name alone (Home from the Wi-Fi) is set on the map first. */
    internal fun tile(t: dev.kb896.aiusage.place.Tile) {
        if (!t.set) { settingTile = t; Log.i(TAG, "prompt: a tile with no place yet — set on the map"); return }
        if (need != PlacePermission.Need.NOTHING) { button(); return }
        pinAt(t.lat!!, t.lng!!, t.radius, t.name)
    }

    /** "Save as tile": the map's middle and zone under [name]. */
    internal fun saveTile(name: String) {
        val (a, b, _) = here()
        val next = Tiles.add(tiles, name, System.currentTimeMillis(), a, b, radius)
        naming = false
        if (next == null) return
        tiles = next
        store.update { it.copy(tiles = next) }
        Log.i(TAG, "prompt: a tile saved (${next.size} now)")
    }

    // -- the pin, the save, the way out --------------------------------------------------------------------------------

    private fun pin() {
        // Nothing chosen yet (a first opening shows the world): the map is to be moved first.
        if (!positioned) { shakes++; return }
        val (a, b, _) = here()
        settingTile?.let { t ->
            tiles = Tiles.place(tiles, t.id, a, b, radius)
            store.update { it.copy(tiles = tiles) }
            pinAt(a, b, radius, t.name)
            return
        }
        pinAt(a, b, radius, Prompt.name(placeName, namedLat, namedLng, a, b, radius))
    }

    /** KEPT FIRST, with its place; the fence asked for; then the usual pin plays (and tells the back), and this window lets go of its blur after. */
    private fun pinAt(a: Double, b: Double, r: Int, name: String?) {
        if (phase == Phase.LEAVING || phase == Phase.CLOSING) return
        // The shared picture still being read (a moment): the tap shakes "Here" and nothing is pinned yet.
        if (reading) { shakes++; return }
        phase = Phase.LEAVING
        hideKeyboard()
        val now = System.currentTimeMillis()
        val where = PinList.Where(a, b, r, name)
        var dropped = false
        // Kept now, TOLD BY THE PIN'S ANIMATION (PinActivity, at its own moment): a note the back sees before is beamed in at once.
        Places.holdForPin(this)
        // On the back's notes page at once where the phone is known to be at that place already (another note there);
        // a new place waits for its fix at pin time (Places.settled), and the pin lands on the note meanwhile.
        val here = Places.hereNow(this)
        Places.write(this, "pinned to a location") { c ->
            val added = PinList.added(c.pins, text, now, where, image = image)
            dropped = added.dropped.any { it.placed }
            c.copy(pins = dev.kb896.aiusage.place.Here.marked(added.pins, here))
        }
        Places.pinned(applicationContext, Arrivals.Place(a, b, Arrivals.zone(r)))
        // A place note pushed out of the twenty: its fence goes with it (the new one is set by Places.pinned).
        if (dropped) dev.kb896.aiusage.place.Fences.sync(applicationContext, "a place note pushed out")
        store.update { it.copy(draft = null) }
        Log.i(TAG, "prompt: pinned to a location (${Arrivals.zoneName(Arrivals.zone(r))}${if (name != null) ", named" else ""}${if (image != null) ", a picture" else ""})")
        lifecycleScope.launch {
            // The controls go (120 ms), the words alone stay; then the pin's own animation, told the note is kept.
            delay(HANDOVER_MS)
            runCatching {
                startActivity(Intent(this@PlacePinActivity, PinActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, text).putExtra(PinActivity.EXTRA_KEPT_AT, now)
                    .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
            }.onFailure {
                Log.w(TAG, "prompt: the pin's animation not started (${it.javaClass.simpleName}) — the note is kept")
                Places.toldByPin(applicationContext)
            }
            // This window keeps its blur until the pin's own is there, then lets go without a transition.
            delay(BLUR_HOLD_MS)
            finish(); overridePendingTransition(0, 0)
        }
    }

    /** Settings' ways in: the tile's place, a new tile, a place note moved. */
    private fun save() {
        if (!positioned) { shakes++; return }
        val (a, b, _) = here()
        when (mode) {
            Mode.TILE -> tileId?.let { id -> store.update { it.copy(tiles = Tiles.place(it.tiles, id, a, b, radius)) } }
            Mode.NEW_TILE -> store.update { s -> Tiles.add(s.tiles, text, System.currentTimeMillis(), a, b, radius)?.let { s.copy(tiles = it) } ?: s }
            Mode.MOVE_PIN -> {
                val here = Places.hereNow(this)
                Places.write(this, "a place note moved") { c -> PinList.moved(c.pins, pinAt, a, b, radius, Prompt.name(placeName, namedLat, namedLng, a, b, radius)).first.let { c.copy(pins = dev.kb896.aiusage.place.Here.marked(it, here)) } }
                Places.pinned(applicationContext, Arrivals.Place(a, b, Arrivals.zone(radius)))
                dev.kb896.aiusage.place.Fences.sync(applicationContext, "a place note moved")
            }
            Mode.PIN -> Unit
        }
        cancel(keepDraft = true)
    }

    /** His own way out: nothing is pinned, no sound; the sheet falls and the window goes. */
    internal fun cancel(keepDraft: Boolean = false) {
        if (phase == Phase.CLOSING || phase == Phase.LEAVING) return
        phase = Phase.CLOSING
        hideKeyboard()
        if (!keepDraft && mode == Mode.PIN) {
            store.update { it.copy(draft = null) }
            // Nothing pinned: the picture read for it goes.
            image?.let { forgetPicture(it) }
        }
        lifecycleScope.launch { delay(CLOSE_MS); finish(); overridePendingTransition(0, 0) }
    }

    override fun onStart() { super.onStart(); map?.view?.onStart() }
    override fun onResume() {
        super.onResume()
        map?.view?.onResume()
        // Back from the permission page or Location's: the word is right before he taps.
        readNeed()
        tiles = store.load().tiles
        // The permission or Location given meanwhile: the fences that were owed are set now.
        dev.kb896.aiusage.place.PlaceWatch.changed(this, "back in the prompt")
    }
    override fun onPause() { map?.view?.onPause(); super.onPause() }   // NOTHING ENDS HERE: only he closes it.
    override fun onStop() { map?.view?.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); map?.view?.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (mode == Mode.PIN && phase != Phase.CLOSING && phase != Phase.LEAVING) store.update { it.copy(draft = draft()) }
    }
    override fun onDestroy() { map?.view?.onDestroy(); map = null; super.onDestroy() }

    companion object {
        private const val TAG = "AiUsagePlace"
        /** The debug extras this screen takes (a debug build, with the shell's token). */
        val DEBUG_EXTRAS = listOf("arrive_demo", "place", "fence_test")
        const val EXTRA_TILE = "dev.chicken.rear.extra.TILE"
        const val EXTRA_NEW_TILE = "dev.chicken.rear.extra.NEW_TILE"
        const val EXTRA_PIN_AT = "dev.chicken.rear.extra.PIN_AT"
        /** The opening (blur, sheet, words) takes this long; the map is made after it. */
        const val OPEN_MS = 520L
        const val HANDOVER_MS = 180L
        const val BLUR_HOLD_MS = 420L
        const val CLOSE_MS = 220L

        private val client by lazy {
            okhttp3.OkHttpClient.Builder().callTimeout(java.time.Duration.ofSeconds(8)).build()
        }
    }
}
