package dev.kb896.aiusage.ui

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What "Pin to back" and "Pin to location" take: words as ever, a picture, a picture with words, several pictures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PinShareTest {
    private val one = Uri.parse("content://media.example/picture/1")
    private val two = Uri.parse("content://media.example/picture/2")

    private fun send(type: String) = Intent(Intent.ACTION_SEND).setType(type)

    @Test fun `words are pinned as they always were`() {
        assertEquals(PinShare.Words("Buy milk"), PinShare.of(send("text/plain").putExtra(Intent.EXTRA_TEXT, "  Buy milk ")))
        assertEquals(PinShare.Words("Selected"), PinShare.of(Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").putExtra(Intent.EXTRA_PROCESS_TEXT, "Selected")))
        assertEquals(PinShare.Nothing, PinShare.of(send("text/plain").putExtra(Intent.EXTRA_TEXT, "   ")))
        assertEquals(PinShare.Nothing, PinShare.of(null))
        // A stream on a text share is not a picture to pin.
        assertEquals(PinShare.Words("words"), PinShare.of(send("text/plain").putExtra(Intent.EXTRA_TEXT, "words").putExtra(Intent.EXTRA_STREAM, one)))
    }

    @Test fun `a picture, and a picture with words as its caption`() {
        assertEquals(PinShare.Pictures(listOf(one), ""), PinShare.of(send("image/jpeg").putExtra(Intent.EXTRA_STREAM, one)))
        assertEquals(PinShare.Pictures(listOf(one), "At the lake"), PinShare.of(send("image/*").putExtra(Intent.EXTRA_STREAM, one).putExtra(Intent.EXTRA_TEXT, " At the lake ")))
        // Only in the clip (some apps): taken from there.
        assertEquals(PinShare.Pictures(listOf(two), ""), PinShare.of(send("image/png").apply { clipData = ClipData.newRawUri("", two) }))
        // A picture share with no picture in it but words: the words.
        assertEquals(PinShare.Words("only words"), PinShare.of(send("image/png").putExtra(Intent.EXTRA_TEXT, "only words")))
        assertEquals(PinShare.Nothing, PinShare.of(send("image/png")))
    }

    @Test fun `a file address is never taken - it could name the app's own private files`() {
        val mine = Uri.parse("file:///data/data/dev.chicken.rear.dev/shared_prefs/panel_config.xml")
        assertEquals(PinShare.Nothing, PinShare.of(send("image/jpeg").putExtra(Intent.EXTRA_STREAM, mine)))
        assertEquals(PinShare.Words("caption"), PinShare.of(send("image/jpeg").putExtra(Intent.EXTRA_STREAM, mine).putExtra(Intent.EXTRA_TEXT, "caption")))
        // Nor an address of the app's own providers, however written: another app could name the app's own files so.
        val id = dev.kb896.aiusage.BuildConfig.APPLICATION_ID
        for (own in listOf("content://$id.pins/pins/1-2x3.jpg", "content://$id/x", "content://0@$id.pins/pins/1-2x3.jpg", "content://${id.uppercase()}.PINS/pins/1-2x3.jpg")) {
            assertEquals(own, PinShare.Nothing, PinShare.of(send("image/jpeg").putExtra(Intent.EXTRA_STREAM, Uri.parse(own))))
        }
        // An app whose name merely begins the same is another app.
        val other = Uri.parse("content://${id}x.files/1")
        assertEquals(PinShare.Pictures(listOf(other), ""), PinShare.of(send("image/jpeg").putExtra(Intent.EXTRA_STREAM, other)))
    }

    @Test fun `several pictures - one note each for the first three, the rest left and counted`() {
        val five = (1..5).map { Uri.parse("content://media.example/picture/$it") }
        val many = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(five)).putExtra(Intent.EXTRA_TEXT, "Our trip")
        assertEquals(PinShare.Pictures(five.take(3), "Our trip", left = 2), PinShare.of(many))
        val twice = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/jpeg").putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(one, two, one))
        assertEquals(PinShare.Pictures(listOf(one, two), ""), PinShare.of(twice))
        assertEquals(PinShare.Nothing, PinShare.of(Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*")))
    }
}
