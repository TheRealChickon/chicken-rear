package dev.kb896.aiusage.rear

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object BackShots {
    /** The folder, as the media store names it (MediaColumns.RELATIVE_PATH). */
    const val RELATIVE_PATH = "DCIM/Screenshots"
    /** The same folder for the shell, through the FUSE mount (a file made there is the media store's to see). */
    const val SHELL_DIR = "/storage/emulated/0/DCIM/Screenshots"
    const val MIME = "image/png"

    private val NAME = Regex("""Screenshot_\d{4}-\d{2}-\d{2}-\d{2}-\d{2}-\d{2}-\d{3}_rear\.png""")

    /** The screenshot taken at [atMs] (wall clock), in the phone's own time zone. */
    fun name(atMs: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val f = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US).apply { timeZone = zone }
        return "Screenshot_${f.format(Date(atMs))}_rear.png"
    }

    /** One of ours, made by [name]: nothing else is let near a shell command or a file name. */
    fun isName(s: String?): Boolean = s != null && NAME.matches(s)

    /** Where the shell writes [name]. */
    fun shellPath(name: String): String = "$SHELL_DIR/$name"

    /**
     * The media store asked to look at [path] now: MediaProvider's own `scan_file` call, which is exactly what
     * MediaScannerConnection.scanFile does since Android 10 (open to any caller, the shell's `content` tool included).
     * The FUSE mount already adds a file made through it as it is closed; this makes sure, and fills in what a scan does.
     */
    fun scanCommand(path: String): String = "content call --uri content://media --method scan_file --arg $path"

    /** Is [name] in the media store's images: the answer is read by [indexed]. */
    fun queryCommand(name: String): String =
        "content query --uri content://media/external/images/media --projection _id --where \"_display_name='$name'\""

    /** `content query`'s answer has a row ("Row: 0 _id=1234"), not "No result found.". */
    fun indexed(out: String): Boolean = ROW.containsMatchIn(out)
    private val ROW = Regex("""Row: \d+ _id=\d+""")

    /**
     * The last resort, only when the scan and the FUSE mount have both left it out: the deprecated scanner broadcast,
     * sent to each media provider package there may be (Google's module, the plain one).
     */
    fun broadcastCommand(path: String): String = PROVIDERS.joinToString("; ") {
        "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://$path -p $it"
    }
    private val PROVIDERS = listOf("com.google.android.providers.media.module", "com.android.providers.media.module")
}
