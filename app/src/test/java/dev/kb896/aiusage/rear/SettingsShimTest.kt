package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The settings a Xiaomi face may read, and nothing it may write: the shell's provider can write as shell. */
class SettingsShimTest {
    @Test fun `reads pass`() {
        for (m in listOf("query", "getType", "canonicalize", "uncanonicalize", "asBinder")) assertTrue(m, SettingsShim.allows(m, null))
        for (c in listOf("GET_system", "GET_secure", "GET_global", "GET_config", "LIST_system", "LIST_secure")) assertTrue(c, SettingsShim.allows("call", c))
    }

    @Test fun `every write is refused`() {
        for (m in listOf("insert", "bulkInsert", "update", "delete", "applyBatch", "openFile", "openAssetFile", "openTypedAssetFile", "refresh", "checkUriPermission"))
            assertFalse(m, SettingsShim.allows(m, null))
        for (c in listOf("PUT_system", "PUT_secure", "PUT_global", "PUT_config", "DELETE_system", "DELETE_global", "RESET_secure", "RESET_global", "SET_ALL_config", "REGISTER_MONITOR_CALLBACK_config", "SET_SYNC_DISABLED_MODE_config"))
            assertFalse(c, SettingsShim.allows("call", c))
        assertFalse("a call with no method", SettingsShim.allows("call", null))
    }

    @Test fun `a call's method is found among its arguments`() {
        assertEquals("GET_secure", SettingsShim.callMethodOf(arrayOf(Any(), "settings", "GET_secure", "theme_rear_widget", null)))
        assertEquals("PUT_global", SettingsShim.callMethodOf(arrayOf("settings", "PUT_global", "x")))
        assertEquals(null, SettingsShim.callMethodOf(arrayOf("settings", "some_name")))
    }
}
