package dev.kb896.aiusage.ui

import android.Manifest
import android.app.ActivityManager
import android.app.AlertDialog
import android.os.Looper
import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.location.LocationManager
import android.os.Bundle
import android.os.PowerManager
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import dev.kb896.aiusage.ui.glass.Backdrop
import dev.kb896.aiusage.service.Graph
import dev.kb896.aiusage.service.NotificationRelay
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-mdpi")
class OnboardingTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun View.labels(): List<TextView> = when (this) {
        is TextView -> listOf(this)
        is ViewGroup -> (0 until childCount).flatMap { getChildAt(it).labels() }
        else -> emptyList()
    }
    private fun words(step: Onboarding.Step, running: Boolean = false, granted: Boolean = false): List<String> {
        val column = LinearLayout(app)
        Onboarding.build(column, step, running, granted, {}, {}, {})
        return column.labels().map { it.text.toString() }
    }

    @Test fun shizukuDistinguishesManagerBinderAndAuthorization() {
        val step = Onboarding.Step.SHIZUKU
        assertTrue("Not installed · stopped" in words(step))
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            packageName = Setup.SHIZUKU_PACKAGE
            applicationInfo = ApplicationInfo().apply { packageName = Setup.SHIZUKU_PACKAGE }
        })
        assertTrue("Installed · stopped" in words(step))
        assertFalse("Allow Chicken Rear" in words(step, false, true))
        assertTrue("Installed · running" in words(step, true))
        assertTrue("Allow Chicken Rear" in words(step, true))
        assertTrue("Chicken Rear · Allowed" in words(step, true, true))
    }

    @Test fun getShizukuOpensTheRecommendedFork() {
        assertTrue(words(Onboarding.Step.SHIZUKU).any { "start on boot and automatic restarts" in it })
        val screen = screen()
        screen.tap("Get Shizuku")
        assertEquals("https://github.com/thedjchi/Shizuku/releases",
            shadowOf(screen.activity).nextStartedActivity.data.toString())
    }

    @Test fun accessReflectsTheListenerAndEachOptionalPermission() {
        val step = Onboarding.Step.NOTIFICATIONS
        assertTrue("Not allowed" in words(step))
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationListenerAccessGranted(
            ComponentName(app, NotificationRelay::class.java), true)
        assertTrue("Allowed" in words(step))
        fun optional() = words(Onboarding.Step.OPTIONAL)
        assertEquals(2, optional().count { it == "Not allowed" })
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        assertTrue(optional().any { "Approximate only" in it })
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.CAMERA)
        assertTrue(optional().any { "while in use" in it })
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        shadowOf(app.getSystemService(LocationManager::class.java)).setLocationEnabled(false)
        assertTrue("Allowed, but Location is off" in optional())
        shadowOf(app.getSystemService(LocationManager::class.java)).setLocationEnabled(true)
        ShadowSettings.setCanDrawOverlays(true)
        assertTrue("Precise, all the time" in optional())
        assertEquals(2, optional().count { it == "Allowed" })
    }

    @Test fun optionalStepOffersBothLanguageChoicesAndPricesEverythingBeforeDownload() {
        val screen = screen()
        screen.tap("Continue")
        screen.tap("Continue")
        screen.tap("Circle to Search language packs")
        val choices = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(0, choices.listView.checkedItemPosition)
        assertEquals(listOf("Only my languages", "Everything"),
            (0 until choices.listView.count).map { choices.listView.adapter.getItem(it).toString() })
        choices.listView.performItemClick(null, 1, choices.listView.adapter.getItemId(1))
        shadowOf(Looper.getMainLooper()).idle()
        val price = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(price.findViewById<TextView>(android.R.id.message).text.contains("1.87 GB total"))
        assertTrue(price.findViewById<TextView>(android.R.id.message).text.contains("free"))
        val wifi = price.window!!.decorView.labels().filterIsInstance<android.widget.CheckBox>().single()
        assertEquals("Wi-Fi only", wifi.text)
        assertTrue(wifi.isChecked)
        price.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(dev.kb896.aiusage.circle.LanguageDownloads.pending(screen.activity))
    }

    @Test fun batteryShowsOnlyReadableAndroidState() {
        val step = Onboarding.Step.BATTERY
        assertTrue("Android battery optimization is on" in words(step))
        shadowOf(app.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(app.packageName, true)
        assertTrue("Android battery optimization is off" in words(step))
        shadowOf(app.getSystemService(ActivityManager::class.java)).setBackgroundRestricted(true)
        assertTrue("Android restricts background use" in words(step))
    }

    private fun screen(): CompanionScreen {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).create().get()
        return CompanionScreen(activity, Graph.of(activity)) {}.also { activity.setContentView(it.root) }
    }

    private fun CompanionScreen.tap(label: String) {
        assertTrue(root.labels().single { it.text.toString() == label }.performClick())
    }

    @Test fun skipIsRememberedAndAdvancedCanReplay() {
        screen().tap("Skip setup")
        val next = screen()
        assertFalse(next.root.labels().any { it.text == "Skip setup" })
        next.open(CompanionScreen.Page.ADVANCED)
        next.tap("Setup guide")
        next.tap("Skip setup")
        assertTrue(next.root.labels().any { it.text == "Setup guide" })
    }

    @Test fun guideOpensTheForkWikiAndRecreationKeepsTheStep() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create()
        val first = controller.get()
        val before = CompanionScreen(first, Graph.of(first)) {}
        first.setContentView(before.root)
        before.tap("Shizuku guide")
        assertEquals("https://github.com/thedjchi/Shizuku/wiki/Setup",
            shadowOf(before.activity).nextStartedActivity.dataString)
        before.tap("Continue")
        before.tap("Continue")

        val state = Bundle()
        controller.start().resume().pause().saveInstanceState(state).stop().destroy()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).create(state).get()
        val after = CompanionScreen(activity, Graph.of(activity)) {}
        assertTrue(after.root.labels().any { it.text == "Camera permission" })
        after.tap("Continue")
        assertTrue(after.root.labels().any { it.text == "Open App info" })
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun renderAndFinishAllFourStepsWithoutGrants() {
        val screen = screen()
        val dir = File("build/onboarding").apply { mkdirs() }
        for (step in Onboarding.Step.entries) {
            val w = 411
            val h = 891
            val root = screen.root
            root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, w, h)
            // Robolectric's Skia cannot compile Android's AGSL refraction shader. Use the real
            // settings gradient and the controls’ software tint; keep the actual view hierarchy.
            val pane = root.getChildAt(0)
            pane.background = BitmapDrawable(app.resources, Backdrop.paint(w, h))
            Backdrop.release()
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            File(dir, "${step.ordinal + 1}-${step.name.lowercase()}.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            screen.tap(if (step == Onboarding.Step.BATTERY) "Done" else "Continue")
        }
        assertFalse(screen().root.labels().any { it.text == "Skip setup" })
    }
}
