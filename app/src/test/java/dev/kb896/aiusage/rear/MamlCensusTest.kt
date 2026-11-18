package dev.kb896.aiusage.rear

import dev.kb896.aiusage.rear.MamlCensus.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Small hand-written manifests in the shape of Xiaomi's (no Xiaomi file is copied here). */
class MamlCensusTest {
    @Test fun `elements, the root's frame rate and updaters`() {
        val c = MamlCensus.of("""<?xml version="1.0" encoding="UTF-8"?>
            <!-- a comment is not read: <SensorBinder/> -->
            <Widget frameRate="0" screenWidth="1080" version="2" useVariableUpdater="HyperMaterial,DateTime.Minute">
              <Var name="pagIndex" expression="0"/>
              <PagImage src="'assets/a.pag'"/>
              <Lottie src="a.json"/>
              <BlurContainer><Image src="a.png"/></BlurContainer>
              <ShaderElement/><PhysicallyBasedRenderingElement/>
              <Text text="#hour24"/>
            </Widget>""")
        assertEquals(setOf(Kind.PAG, Kind.LOTTIE, Kind.BLUR, Kind.GL, Kind.PBR), c.kinds)
        assertEquals(0, c.frameRate)
        assertEquals(setOf("HyperMaterial", "DateTime.Minute"), c.updaters)
        assertEquals(false, c.sensors)
    }

    @Test fun `a Video bound to his video is his, one with the theme's own source is the theme's`() {
        val user = MamlCensus.of("""<Widget><Video name="customVideo" path="@videoUri_X" w="#view_width"/></Widget>""")
        assertEquals(setOf(Kind.USER_VIDEO), user.kinds)
        val theme = MamlCensus.of("""<Widget><Video name="bgVideo"/><VideoCommand target="bgVideo" command="config" path="@videoPath"/></Widget>""")
        assertEquals(setOf(Kind.THEME_VIDEO), theme.kinds)
        val both = MamlCensus.of("""<Widget><Video name="aiVideo"/><VideoCommand target="aiVideo" command="config" path="'assets/ai/ai.mp4'"/>
            <Video visibility="1" path="@videoUri_IEe5" name="other"/></Widget>""")
        assertEquals(setOf(Kind.THEME_VIDEO, Kind.USER_VIDEO), both.kinds)
        val byCommand = MamlCensus.of("""<Widget><Video name="v"/><VideoCommand target="v" command="config" path="@videoUri"/></Widget>""")
        assertEquals(setOf(Kind.USER_VIDEO), byCommand.kinds)
    }

    @Test fun `binders - providers by authority, sensors, broadcasts, files, settings`() {
        val c = MamlCensus.of("""<Widget><VariableBinders>
            <ContentProviderBinder name="MiSteps" uriExp="'content://com.mi.health.provider.main/activity/steps/brief'" columns="steps,goal"/>
            <ContentProviderBinder name="S" uri="content://settings/system"/>
            <SensorBinder type="accelerometer"/>
            <BroadcastBinder action="android.intent.action.USER_UNLOCKED"/>
            <FileBinder/><SettingsBinder/>
            </VariableBinders></Widget>""")
        assertEquals(setOf("com.mi.health.provider.main", "settings"), c.providers)
        assertTrue(c.sensors && c.files && c.settings)
        assertEquals(setOf("android.intent.action.USER_UNLOCKED"), c.broadcasts)
        assertEquals(listOf("needs com.mi.health.provider.main", "needs a sensor"), MamlCensus.unreadable(c))
    }

    @Test fun `the engine's own variables it reads, but not the ones it declares itself`() {
        val c = MamlCensus.of("""<Widget><Var name="steps_count" expression="0"/>
            <Text text="#battery_level"/><Text text="#call_missed_count"/><Text visibility="#__darkmode"/><Text text="#steps_count"/></Widget>""")
        assertEquals(setOf("battery_level", "call_missed_count", "__darkmode"), c.variables)
        assertEquals(listOf("needs #call_missed_count"), MamlCensus.unreadable(c))
        // Battery level and dark mode are fed; a canvas face that reads only them is readable.
        assertEquals(emptyList<String>(), MamlCensus.unreadable(MamlCensus.of("""<Widget><Text text="#battery_level"/></Widget>""")))
    }
}
