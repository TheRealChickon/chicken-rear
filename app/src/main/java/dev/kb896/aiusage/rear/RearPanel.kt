package dev.kb896.aiusage.rear

import android.os.IBinder
import android.util.Log
import java.lang.reflect.Method

/**
 * How bright the rear PANEL itself is at this moment, in nits: Xiaomi's automatic brightness sets it,
 * and it is NOT the same in the same light. Seen on the phone (`dumpsys display`, rear display, 0 lux
 * each time): 35.2 nits on one evening and the next morning; 1.0 to 2.0 nits in the night between. A share of 35 nits
 * is a dim back; the same share of 1 nit is no back at all. So the share [RearLight] gives is checked against what
 * the panel has ([RearLight.light]'s `panelNits`).
 *
 * Read from the display manager (`IDisplayManager.getBrightness(displayId)`, the number
 * `cmd display get-brightness --id 1` prints), one binder call, by reflection looked up once. Turned
 * into nits with the rear panel's own table ([NITS], from `dumpsys display`: "mBacklight=… mNits=…" of
 * the rear display's device config). Reads only: the panel's brightness is never set from here.
 * Refused or missing: NaN, said once, and the back goes by its share alone, as it did.
 */
object RearPanel {
    private const val TAG = "AiUsageRear"

    /** The rear panel: its brightness number (0..1) and the nits there. Straight lines between. */
    val NITS: List<Pair<Float, Float>> = listOf(
        3.66256E-4f to 1f,
        0.37486264f to 600f,
        0.49993896f to 800f,
        0.8240142f to 2_000f,
        1f to 3_500f,
    )

    /** Nits at brightness number [brt]; NaN for one that is none (the display manager says -1 for "off"). */
    fun nits(brt: Float): Float {
        if (brt.isNaN() || brt < 0f) return Float.NaN
        if (brt <= NITS.first().first) return NITS.first().second
        if (brt >= NITS.last().first) return NITS.last().second
        val i = NITS.indexOfLast { it.first <= brt }
        val (b0, n0) = NITS[i]
        val (b1, n1) = NITS[i + 1]
        return n0 + (n1 - n0) * (brt - b0) / (b1 - b0)
    }

    private class Api(val display: Any, val get: Method)

    private var refused = false
    private val api: Api? by lazy {
        runCatching {
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, "display") as IBinder
            val display = Class.forName("android.hardware.display.IDisplayManager\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
            Api(display, Class.forName("android.hardware.display.IDisplayManager").getMethod("getBrightness", Int::class.javaPrimitiveType))
        }.onFailure { Log.w(TAG, "light: the panel's own level cannot be read on this firmware (${(it.cause ?: it).message})") }.getOrNull()
    }

    /** The panel's nits now on logical display [displayId]; NaN when it cannot be had. */
    fun read(displayId: Int): Float {
        if (refused) return Float.NaN
        val a = api ?: return Float.NaN
        return runCatching { nits(a.get.invoke(a.display, displayId) as Float) }.getOrElse {
            refused = true
            Log.w(TAG, "light: the panel's own level is not given to the shell (${(it.cause ?: it).message})")
            Float.NaN
        }
    }
}
