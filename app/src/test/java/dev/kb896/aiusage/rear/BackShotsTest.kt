package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.TimeZone

/**
 * A screenshot of the back goes beside the phone's own screenshots, in DCIM/Screenshots, named as the phone names them
 * (Screenshot_2001-02-03-05-05-06-789_<package>.jpg there): Screenshot_<the same>_rear.png. When the app does not take it
 * in time, the shell saves it there itself and has the media store index it.
 */
class BackShotsTest {

    @Test fun `named as the phone names its own screenshots, to the millisecond, in the phone's time`() {
        val at = 981_173_106_789L
        assertEquals("Screenshot_2001-02-03-04-05-06-789_rear.png", BackShots.name(at, TimeZone.getTimeZone("UTC")))
        assertEquals("Screenshot_2001-02-03-05-05-06-789_rear.png", BackShots.name(at, TimeZone.getTimeZone("Europe/Berlin")))
        // Zero-padded everywhere, so they sort as the phone's do.
        assertEquals("Screenshot_2026-01-02-03-04-05-006_rear.png", BackShots.name(1_767_323_045_006L, TimeZone.getTimeZone("UTC")))
    }

    @Test fun `only a name of ours is one`() {
        assertTrue(BackShots.isName("Screenshot_2001-02-03-05-05-06-789_rear.png"))
        assertTrue(BackShots.isName(BackShots.name(System.currentTimeMillis())))
        assertFalse(BackShots.isName(null))
        assertFalse(BackShots.isName("Screenshot_2001-02-03-05-05-06-789_com.whatsapp.jpg"))
        assertFalse(BackShots.isName("Back_20010203_050506.png"))
        assertFalse(BackShots.isName("Screenshot_2001-02-03-05-05-06-789_rear.png; rm -rf /"))
        assertFalse(BackShots.isName("../Screenshot_2001-02-03-05-05-06-789_rear.png"))
    }

    @Test fun `into DCIM Screenshots - for the media store and for the shell`() {
        assertEquals("DCIM/Screenshots", BackShots.RELATIVE_PATH)
        assertEquals("/storage/emulated/0/DCIM/Screenshots/Screenshot_2001-02-03-05-05-06-789_rear.png", BackShots.shellPath("Screenshot_2001-02-03-05-05-06-789_rear.png"))
        assertTrue(BackShots.SHELL_DIR.endsWith("/" + BackShots.RELATIVE_PATH))
        assertEquals("image/png", BackShots.MIME)
    }

    @Test fun `the media store - its scan_file call, a query that checks, the old broadcast last`() {
        val p = "/storage/emulated/0/DCIM/Screenshots/Screenshot_2001-02-03-05-05-06-789_rear.png"
        assertEquals("content call --uri content://media --method scan_file --arg $p", BackShots.scanCommand(p))
        val q = BackShots.queryCommand("Screenshot_2001-02-03-05-05-06-789_rear.png")
        assertTrue(q, q.startsWith("content query --uri content://media/external/images/media "))
        assertTrue(q, q.contains("_display_name='Screenshot_2001-02-03-05-05-06-789_rear.png'"))
        assertTrue(BackShots.indexed("Row: 0 _id=1234\n"))
        assertFalse(BackShots.indexed("No result found.\n"))
        assertFalse(BackShots.indexed(""))
        assertFalse(BackShots.indexed("Error while accessing provider:media"))
        val b = BackShots.broadcastCommand(p)
        assertTrue(b, b.contains("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://$p -p com.google.android.providers.media.module"))
        assertTrue(b, b.contains("-p com.android.providers.media.module"))
    }

    private fun src(path: String) = listOf("src/main/java/dev/kb896/aiusage/$path", "app/src/main/java/dev/kb896/aiusage/$path").map(::File).first { it.isFile }.readText()

    @Test fun `the app saves a screenshot there, by that name, and the old folder and name are gone`() {
        val store = src("rear/CaptureStore.kt")
        assertTrue(store.contains("MediaStore.MediaColumns.RELATIVE_PATH, BackShots.RELATIVE_PATH"))
        assertTrue(store.contains("BackShots.name(System.currentTimeMillis())"))
        assertFalse(store.contains("Pictures/Screenshots"))
        assertFalse(store.contains("\"Back_\$stamp.\${if (video)"))
        val shell = src("rear/RearUserService.kt")
        assertFalse(shell.contains("shot_\${System.currentTimeMillis()}.png"))
        // The shell's own save: through the FUSE mount, then the media store's scan, checked by a query.
        val save = shell.substringAfter("private fun indexShot(").substringBefore("\n    }")
        assertTrue(save, save.indexOf("scanCommand") < save.indexOf("queryCommand"))
        assertTrue(save, save.indexOf("queryCommand") < save.indexOf("broadcastCommand"))
    }
}
