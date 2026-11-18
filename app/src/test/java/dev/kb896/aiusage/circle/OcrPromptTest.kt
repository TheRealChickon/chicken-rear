package dev.kb896.aiusage.circle

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS")
class OcrPromptTest {
    @Test fun firstUsePricesOnlyLocalScriptsAndDecliningCreatesNoFiles() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val c = controller.get()
        try {
            assertEquals(OcrModel.FAST, OcrModels.model(c))
            assertFalse(OcrPrompt.accurate(c))
            OcrModels.selectModel(c, OcrModel.ACCURATE)
            assertEquals(OcrModel.ACCURATE, OcrModels.model(c))
            assertTrue(OcrModels.status.value.task.isEmpty())
            assertTrue(OcrPrompt.accurate(c))
            val accurate = ShadowAlertDialog.getLatestAlertDialog()
            val price = OcrPrompt.mb(OcrCatalog.accuratePacks.sumOf { it.bytes })
            waitForPrice(accurate, price)
            assertTrue(accurate.findViewById<TextView>(android.R.id.message).text.contains("Accurate Chinese and Japanese"))
            accurate.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(OcrModels.status.value.task.isEmpty())
            assertTrue(OcrModels.root(c).listFiles().isNullOrEmpty())

            assertTrue(OcrPrompt.show(c))
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val initial = OcrModels.initial(c)
            assertEquals(listOf("det","latin"), initial.map { it.name })
            waitForPrice(dialog, OcrPrompt.mb(initial.sumOf { it.bytes }))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(dialog.isShowing)
            assertFalse(OcrModels.status.value.busy)
            assertTrue(OcrModels.root(c).listFiles().isNullOrEmpty())

            OcrPrompt.suggest(c,listOf("kannada")) {}
            val extra = ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(extra.isShowing)
            assertTrue(OcrModels.offered(c,"kannada"))
            extra.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            OcrPrompt.suggest(c,listOf("kannada")) {}
            assertFalse(ShadowAlertDialog.getLatestAlertDialog().isShowing)
            assertTrue(OcrModels.root(c).listFiles().isNullOrEmpty())
        } finally {
            OcrPrompt.dismiss(c)
            controller.pause().stop().destroy()
        }
    }

    private fun waitForPrice(dialog: AlertDialog, price: String) {
        val message = dialog.findViewById<TextView>(android.R.id.message)
        val deadline = System.nanoTime() + 4_000_000_000L
        while (!message.text.contains(price) && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue(message.text.toString(), message.text.contains(price))
    }
}
