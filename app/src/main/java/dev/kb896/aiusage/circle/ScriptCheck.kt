package dev.kb896.aiusage.circle

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.abs

/** A script hint, never transcription. Compare a few silhouettes to the phone's own fallback fonts.
 * Leave an ambiguous line unidentified; low CTC confidence can then offer the language picker. */
internal class ScriptCheck {
    private data class Sample(val pack: String, val bits: Long, val aspect: Int)
    private val samples by lazy { templates() }
    private data class Run(val pack: String, val bands: DoubleArray, val patches: LongArray)
    private val profiles by lazy { profileTemplates() }

    // Common connected forms, ascenders and combining marks. Android shapes these samples with
    // the same script fallback fonts used by its UI. No language or script network is shipped.
    private val phrases = mapOf(
        "latin" to "Network settings|Bluetooth devices|Gestion des comptes|Bildschirm entsperren",
        "el" to "Ρυθμίσεις οθόνης|Συνδεδεμένες συσκευές|Ειδοποιήσεις και ήχοι|Ελληνική γλώσσα",
        "cyrillic" to "Настройки телефона|Управление звуком|Подключение устройств|Главное меню",
        "arabic" to "إعدادات الهاتف|الأجهزة المتصلة|اللغة والإدخال|فتح الشاشة الرئيسية",
        "devanagari" to "मोबाइल सेटिंग्स|संपर्क जानकारी|प्रदर्शन और ध्वनि|संदेश खोलें",
        "th" to "การตั้งค่าโทรศัพท์|การแจ้งเตือน|อุปกรณ์ที่เชื่อมต่อ|ความเป็นส่วนตัว",
        "ta" to "திரை அமைப்புகள்|புதிய செய்தி|கணக்கின் விவரங்கள்|தொலைபேசி எண்",
        "te" to "ఫోన్ సెట్టింగ్‌లు|ఖాతా వివరాలు|ప్రధాన తెర|పరికర సమాచారం",
        "kannada" to "ಸಾಧನದ ಮಾಹಿತಿ|ಪರದೆಯ ಹೊಳಪು|ಖಾತೆಯ ವಿವರಗಳು|ದೂರವಾಣಿ ಸಂಖ್ಯೆ",
        "korean" to "화면의 밝기|기기 연결|새로운 메시지|안전 및 개인정보",
        "cjk" to "屏幕显示和亮度|通知设置|画面を開く|语言和地区"
    )

    private val alphabets = mapOf(
        "latin" to "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyzáéöüñ",
        "el" to "ΑΒΓΔΕΖΗΘΙΚΛΜΝΞΟΠΡΣΤΥΦΧΨΩαβγδεζηθικλμνξοπρστυφχψω",
        "cyrillic" to "АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯабвгдеёжзийклмнопрстуфхцчшщъыьэюя",
        "arabic" to "ابتثجحخدذرزسشصضطظعغفقكلمنهويپچژگ",
        "devanagari" to "अआइईउऊएऐओऔकखगघचछजझटठडढणतथदधनपफबभमयरलवशषसह",
        "th" to "กขฃคฅฆงจฉชซฌญฎฏฐฑฒณดตถทธนบปผฝพฟภมยรลวศษสหฬอฮ",
        "ta" to "அஆஇஈஉஊஎஏஐஒஓஔகஙசஞடணதநபமயரலவழளறன",
        "te" to "అఆఇఈఉఊఎఏఐఒఓఔకఖగఘచఛజఝటఠడఢణతథదధనపఫబభమయరలవశషసహ",
        "kannada" to "ಅಆಇಈಉಊಎಏಐಒಓಔಕಖಗಘಚಛಜಝಟಠಡಢಣತಥದಧನಪಫಬಭಮಯರಲವಶಷಸಹ",
        "korean" to "가나다라마바사아자차카타파하설정검색화면네트워크밝기표시알림한국어연결사용앱정보계정",
        "cjk" to "设置搜尋設定顯示显示亮度無線網路蓝牙通知与状态栏网络和互联网屏幕系统時間安全中文语言日本語あいうえおかきくけこさしすせそタチツテトカメラ",
    )

    private fun signature(ink: BooleanArray, stride: Int, x0: Int, x1: Int, height: Int): Pair<Long,Int>? {
        var top = height
        var bottom = -1
        for (y in 0 until height) for (x in x0 until x1) if (ink[y*stride+x]) {
            top = minOf(top,y); bottom = maxOf(bottom,y)
        }
        val h = bottom-top+1; val w = x1-x0
        if (h < 7 || w < 2 || w > h*2) return null
        val counts = IntArray(64)
        for (y in top..bottom) for (x in x0 until x1) if (ink[y*stride+x])
            counts[((y-top)*8/h)*8+(x-x0)*8/w]++
        var bits = 0L
        for (i in counts.indices) if (counts[i]*64 >= w*h*.26f) bits = bits or (1L shl i)
        return bits to w*16/h
    }

    private fun inkMask(pixels: IntArray, width: Int): BooleanArray {
        fun luma(p: Int) = (((p ushr 16) and 255)*77 + ((p ushr 8) and 255)*150 + (p and 255)*29) ushr 8
        val edges = IntArray(width*2) { luma(pixels[if (it < width) it else pixels.size-2*width+it]) }.sortedArray()
        val bg = edges[width]
        return BooleanArray(pixels.size) { abs(luma(pixels[it])-bg) > 48 }
    }

    fun inkColumns(pixels: IntArray, width: Int): BooleanArray {
        val ink = inkMask(pixels,width)
        return BooleanArray(width) { x -> (0 until pixels.size/width).any { ink[it*width+x] } }
    }

    private fun silhouettes(pixels: IntArray, width: Int): List<Pair<Long,Int>> {
        val height = pixels.size/width
        val ink = inkMask(pixels,width)
        val out = mutableListOf<Pair<Long,Int>>()
        var start = -1
        for (x in 0..width) {
            val lit = x < width && (0 until height).any { ink[it*width+x] }
            if (lit && start < 0) start = x
            if (!lit && start >= 0) {
                signature(ink,width,start,x,height)?.let(out::add)
                start = -1
            }
            if (out.size >= 24) break
        }
        return out
    }

    private fun templates(): List<Sample> {
        val bitmap = Bitmap.createBitmap(64,48,Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 32f }
        val pixels = IntArray(64*48)
        val result = mutableListOf<Sample>()
        try {
            for ((pack,letters) in alphabets) for (c in letters) {
                if (!paint.hasGlyph(c.toString())) continue
                canvas.drawColor(Color.WHITE)
                canvas.drawText(c.toString(),4f,36f,paint)
                bitmap.getPixels(pixels,0,64,0,0,64,48)
                for ((bits,aspect) in silhouettes(pixels,64)) result.add(Sample(pack,bits,aspect))
            }
        } finally { bitmap.recycle() }
        return result
    }

    // Ink density and horizontal/vertical stroke transitions in eight bands. Comparing text
    // runs covers joined scripts where finding a separate silhouette per letter is impossible.
    private fun profile(pack: String, ink: BooleanArray, w: Int): Run? {
        val h = ink.size / w
        var l = w; var r = -1; var t = h; var b = -1
        for (i in ink.indices) if (ink[i]) {
            val x = i%w; val y = i/w
            l = minOf(l,x); r = maxOf(r,x); t = minOf(t,y); b = maxOf(b,y)
        }
        if (b-t < 6 || r-l < b-t) return null
        val height = b-t+1
        val width = r-l+1
        val bins = DoubleArray(32)
        for (y in t..b) {
            val band = 8*(y-t)/height
            var filled = 0
            for (x in l..r) {
                val i = y*w+x
                if (!ink[i]) continue
                filled++
                bins[band]++
                if (x == l || !ink[i-1]) bins[8+band]++
                if (y == t || !ink[i-w]) bins[16+band]++
            }
            bins[24+band] = maxOf(bins[24+band],filled.toDouble()/width)
        }
        for (j in 0 until 24) bins[j] *= when (j/8) {
            0 -> 8.0/(width*height)
            else -> 1.6/width
        }
        // Local stroke patterns in overlapping half-height windows complement the baseline
        // profile, e.g. the loops and upper marks of Thai versus Cyrillic lowercase letters.
        val patches = mutableListOf<Long>()
        val span = maxOf(4,height/2)
        for (left in l..r-span+1 step maxOf(2,span/3)) {
            val cells = IntArray(64)
            for (y in t..b) for (x in left until left+span) if (ink[y*w+x])
                cells[(y-t)*8/height*8 + (x-left)*8/span]++
            var bits = 0L
            for (i in cells.indices) if (cells[i]*64 >= height*span*.24) bits = bits or (1L shl i)
            if (java.lang.Long.bitCount(bits) >= 6) patches.add(bits)
        }
        return Run(pack,bins,patches.toLongArray())
    }

    private fun profileTemplates(): List<Run> {
        val w = 520
        val bitmap = Bitmap.createBitmap(w,60,Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 33f; color = Color.BLACK }
        val px = IntArray(w*60)
        val out = mutableListOf<Run>()
        try {
            for ((name,text) in phrases) for (phrase in text.split('|')) for (bold in listOf(false,true)) {
                paint.isFakeBoldText = bold
                canvas.drawColor(Color.WHITE)
                canvas.drawText(phrase,6f,42f,paint)
                bitmap.getPixels(px,0,w,0,0,w,60)
                profile(name,inkMask(px,w),w)?.let(out::add)
            }
        } finally { bitmap.recycle() }
        return out
    }

    fun match(pixels: IntArray, width: Int): String? {
        val votes = mutableMapOf<String,Int>()
        val shapes = silhouettes(pixels,width)
        for ((bits,aspect) in shapes) {
            val distances = mutableMapOf<String,Int>()
            for (s in samples) {
                val d = java.lang.Long.bitCount(bits xor s.bits) + abs(aspect-s.aspect)
                if (d < (distances[s.pack] ?: 100)) distances[s.pack] = d
            }
            val ranked = distances.entries.sortedBy { it.value }
            if (ranked.size < 2) continue
            val first = ranked[0]
            if (first.value <= 13 && ranked[1].value-first.value >= 3)
                votes[first.key] = (votes[first.key] ?: 0)+1
        }
        val best = votes.maxByOrNull { it.value }
        if (best != null && best.value >= 3 && best.value*3 >= votes.values.sum()*2) return best.key
        val target = profile("",inkMask(pixels,width),width) ?: return best?.key?.takeIf { best.value >= 2 }
        val ranked = profiles.groupBy { it.pack }.mapValues { (pack,samples) ->
            val bands = samples.map { sample -> sample.bands.indices.sumOf { i ->
                val delta = sample.bands[i]-target.bands[i]
                delta*delta
            } }.sorted().take(2).average()
            var distances = 0.0
            for (patch in target.patches) {
                var nearest = 64
                for (sample in samples) for (ref in sample.patches)
                    nearest = minOf(nearest,java.lang.Long.bitCount(patch xor ref))
                distances += nearest
            }
            val texture = distances / (target.patches.size.coerceAtLeast(1)*12)
            .6*bands + texture - .06*(votes[pack] ?: 0).coerceAtMost(4)
        }.entries.sortedBy { it.value }
        val first = ranked.first()
        return first.key.takeIf { first.value < .92*ranked[1].value ||
            best?.key == first.key && first.value < .98*ranked[1].value }
    }
}
