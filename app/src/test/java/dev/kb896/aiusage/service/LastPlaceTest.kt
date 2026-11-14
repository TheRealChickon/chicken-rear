package dev.kb896.aiusage.service

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The rough position kept for the weather: nothing before the first, then a tenth of a degree and no finer. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class LastPlaceTest {
    @Test
    fun `nothing is known before the phone has given a position`() {
        assertNull(LastPlace.read(ApplicationProvider.getApplicationContext()))
    }

    @Test
    fun `what is kept is a tenth of a degree and no finer`() {
        val c = ApplicationProvider.getApplicationContext<android.content.Context>()
        LastPlace.keep(c, 12.34567, -98.76543)
        assertEquals(12.3 to -98.8, LastPlace.read(c))
    }
}
