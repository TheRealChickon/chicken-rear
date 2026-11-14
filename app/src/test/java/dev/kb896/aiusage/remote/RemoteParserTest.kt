package dev.kb896.aiusage.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteParserTest {

    private fun ok(cmd: String, vararg args: Pair<String, String>): RemoteCommand =
        (RemoteParser.parse(cmd, mapOf(*args)) as RemoteParser.Result.Ok).command

    private fun bad(cmd: String?, vararg args: Pair<String, String>): String =
        (RemoteParser.parse(cmd, mapOf(*args)) as RemoteParser.Result.Bad).reason

    @Test fun `a page by its name, waking unless told not to`() {
        assertEquals(RemoteCommand.Page(PageName.USAGE, wake = true), ok("page", "name" to "usage"))
        assertEquals(RemoteCommand.Page(PageName.CLOCK, wake = false), ok("PAGE", "name" to "Clock", "wake" to "0"))
        assertTrue(bad("page", "name" to "3").startsWith("page"))
        assertTrue(bad("page").startsWith("page"))
    }

    @Test fun `home assistant's typed extras read the same as strings`() {
        // HA sends ring:1 as an Integer and wake:true as a Boolean; both arrive as toString().
        assertEquals(RemoteCommand.Wake(guard = false), ok("wake", "guard" to "false"))
        assertEquals(0, (ok("ring.set", "ring" to "1", "value" to "0.5") as RemoteCommand.RingSet).ring)
        assertEquals(1, (ok("ring.set", "ring" to "2", "value" to "50") as RemoteCommand.RingSet).ring)
    }

    @Test fun `a switch is on, off or the other one, and toggles when it says nothing`() {
        assertEquals(RemoteCommand.AlwaysOn(Switch.ON, now = true), ok("aod", "to" to "on", "now" to "1"))
        assertEquals(RemoteCommand.AlwaysOn(Switch.OFF, now = false), ok("aod", "state" to "false"))
        assertEquals(RemoteCommand.AlwaysOn(Switch.TOGGLE, now = false), ok("aod"))
        assertEquals(RemoteCommand.KeepAwake(Switch.TOGGLE), ok("keep_awake", "to" to "toggle"))
        assertEquals(true, Switch.TOGGLE.applied(false))
        assertEquals(false, Switch.OFF.applied(true))
    }

    @Test fun `brightness is clamped to what the rear takes, and needs something to set`() {
        assertEquals(RemoteCommand.Brightness(255, null), ok("brightness", "value" to "900"))
        assertEquals(RemoteCommand.Brightness(1, null), ok("brightness", "value" to "0"))
        assertEquals(RemoteCommand.Brightness(null, true), ok("brightness", "auto" to "on"))
        assertTrue(bad("brightness").startsWith("brightness"))
        assertTrue(bad("brightness", "value" to "bright").startsWith("brightness"))
    }

    @Test fun `an app is a package name and nothing a shell could read otherwise`() {
        assertEquals(RemoteCommand.AppOpen("com.spotify.music"), ok("app.open", "package" to "com.spotify.music"))
        assertTrue(bad("app.open", "package" to "com.spotify.music; reboot").startsWith("app.open"))
        assertTrue(bad("app.open", "package" to "spotify").startsWith("app.open"))
        assertTrue(bad("app.open", "package" to "com.a/.Main").startsWith("app.open"))
        assertEquals(RemoteCommand.AppAction("close"), ok("app.close"))
        assertEquals(RemoteCommand.AppAction("front"), ok("app.to_front"))
        assertEquals(RemoteCommand.AppAction("panel"), ok("app.hide"))
    }

    @Test fun `mrss's words mean what they meant there`() {
        assertEquals(RemoteCommand.AppOpen("com.example.app"), ok("switch", "packageName" to "com.example.app"))
        assertEquals(RemoteCommand.AppSendCurrent, ok("switch", "current" to "1"))
        assertEquals(RemoteCommand.AppAction("front"), ok("return", "current" to "1"))
        assertEquals(RemoteCommand.Screenshot, ok("screenshot"))
        assertTrue(bad("switch").startsWith("switch"))
    }

    @Test fun `a card needs words, keeps an id, and is cut to length`() {
        val c = ok("card.show", "id" to "door", "title" to "Front door", "text" to "x".repeat(900), "wake" to "true") as RemoteCommand.CardShow
        assertEquals("door", c.id)
        assertEquals(RemoteParser.MAX_TEXT, c.text.length)
        assertTrue(c.wake)
        assertEquals("card", (ok("card.show", "title" to "Hi") as RemoteCommand.CardShow).id)
        // an id becomes part of a key: one with a separator in it is not taken
        assertEquals("card", (ok("card.show", "title" to "Hi", "id" to "a b:c") as RemoteCommand.CardShow).id)
        assertTrue(bad("card.show", "id" to "x").startsWith("card.show"))
        assertEquals(RemoteCommand.CardClear(""), ok("card.clear_all"))
    }

    @Test fun `a tile has an id, something to show, and a colour that is one`() {
        val t = ok("tile.set", "id" to "temp", "label" to "Living room", "value" to "21.5°", "color" to "#FF8800", "tap" to "1") as RemoteCommand.TileSet
        assertEquals(0xFFFF8800.toInt(), t.color)
        assertTrue(t.tap)
        assertTrue(bad("tile.set", "label" to "x").startsWith("tile.set"))
        assertTrue(bad("tile.set", "id" to "a").startsWith("tile.set"))
        assertTrue(bad("tile.set", "id" to "a", "value" to "1", "color" to "orange").startsWith("tile.set"))
        assertEquals(RemoteCommand.TileRemove(""), ok("tile.clear"))
    }

    @Test fun `a gauge takes a fraction or a percentage, or counts down`() {
        assertEquals(0.63f, (ok("ring.set", "ring" to "1", "value" to "0.63") as RemoteCommand.RingSet).value)
        assertEquals(0.63f, (ok("ring.set", "ring" to "1", "value" to "63%") as RemoteCommand.RingSet).value)
        assertEquals(0.63f, (ok("ring.set", "ring" to "1", "value" to "63") as RemoteCommand.RingSet).value)
        assertEquals(1f, (ok("ring.set", "ring" to "1", "value" to "1") as RemoteCommand.RingSet).value)
        val countdown = ok("ring.set", "ring" to "2", "ends" to "1800000000") as RemoteCommand.RingSet
        assertNull(countdown.value)
        assertEquals(1_800_000_000_000L, countdown.endsAt)
        assertTrue(bad("ring.set", "ring" to "3", "value" to "1").startsWith("ring.set"))
        assertTrue(bad("ring.set", "ring" to "1").startsWith("ring.set"))
        assertTrue(bad("ring.set", "ring" to "1", "value" to "-1").startsWith("ring.set"))
        assertEquals(RemoteCommand.RingClear(-1), ok("ring.clear"))
        assertEquals(RemoteCommand.RingClear(1), ok("ring.clear", "ring" to "2"))
    }

    @Test fun `times are seconds or milliseconds, told apart by size`() {
        assertEquals(1_800_000_000_000L, RemoteParser.epochMs("1800000000"))
        assertEquals(1_800_000_000_000L, RemoteParser.epochMs("1800000000000"))
        assertNull(RemoteParser.epochMs("soon"))
        assertNull(RemoteParser.epochMs("0"))
    }

    @Test fun `json is an object of a sane size, or it is not taken`() {
        assertEquals(RemoteCommand.ConfigSet("""{"weather":"rain"}"""), ok("config.set", "json" to """ {"weather":"rain"} """))
        assertTrue(bad("config.set", "json" to "[1]").startsWith("config.set"))
        assertTrue(bad("config.set", "json" to "{" + "x".repeat(RemoteParser.MAX_JSON) + "}").startsWith("config.set"))
        assertTrue(bad("usage.push", "json" to "{}").startsWith("usage.push"))
        assertEquals(RemoteCommand.UsagePush("gemini", "{}"), ok("usage.push", "provider" to "gemini", "json" to "{}"))
    }

    @Test fun `media speaks the watcher's words`() {
        assertEquals(RemoteCommand.Media("playpause"), ok("media", "action" to "toggle"))
        assertEquals(RemoteCommand.Media("previous"), ok("media", "action" to "prev"))
        assertTrue(bad("media", "action" to "eject").startsWith("media"))
    }

    @Test fun `nothing, and anything unknown, is refused without being echoed`() {
        assertEquals("no command", bad(null))
        assertEquals("no command", bad("  "))
        assertEquals("unknown command", bad("rm -rf"))
    }

    @Test fun `every command names its scope`() {
        assertEquals(RemoteScope.DISPLAY, ok("sleep").scope)
        assertEquals(RemoteScope.APPS, ok("camera").scope)
        assertEquals(RemoteScope.CONTENT, ok("tile.clear").scope)
        assertEquals(RemoteScope.LOOK, ok("scene.apply", "name" to "night").scope)
        assertEquals(RemoteScope.CAPTURE, ok("record").scope)
        assertEquals(RemoteScope.QUERY, ok("state").scope)
    }

    @Test fun `a link is the command, its arguments and its token`() {
        val l = RemoteParser.link("chickenrear://page?name=usage&token=abc_-123&wake=0")!!
        assertEquals("page", l.cmd)
        assertEquals(mapOf("name" to "usage", "wake" to "0"), l.args)
        assertEquals("abc_-123", l.token)
    }

    @Test fun `a link's path is part of its command, and its text is decoded`() {
        val l = RemoteParser.link("chickenrear://card/show?title=Front%20door&text=Open%2C+since+9%3A40")!!
        assertEquals("card.show", l.cmd)
        assertEquals("Front door", l.args["title"])
        assertEquals("Open, since 9:40", l.args["text"])
        assertNull(l.token)
    }

    @Test fun `a link of another scheme, or with no command, is not ours`() {
        assertNull(RemoteParser.link("mrss://switch?current=1"))
        assertNull(RemoteParser.link("https://example.com/?cmd=wake"))
        assertNull(RemoteParser.link("chickenrear://"))
        assertNull(RemoteParser.link("chickenrear://?token=x"))
    }
}
