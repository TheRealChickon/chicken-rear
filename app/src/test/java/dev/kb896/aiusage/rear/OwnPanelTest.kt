package dev.kb896.aiusage.rear

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnPanelTest {

    private val release = "dev.chicken.rear"
    private val dev = "dev.chicken.rear.dev"

    @Test fun `a component in any of the spellings seen`() {
        assertTrue(OwnPanel.names("taskId=1: dev.chicken.rear/dev.kb896.aiusage.rear.RearActivity bounds=[0,0][1,1]", release))
        assertTrue(OwnPanel.names("topActivity=ComponentInfo{dev.chicken.rear.dev/dev.kb896.aiusage.rear.RearActivity}", dev))
        assertTrue(OwnPanel.names(" VRI-dev.chicken.rear/dev.kb[...]rear.RearActivity#1187", release))
    }

    @Test fun `the release's package is not found inside the dev build's, nor the other way`() {
        assertFalse(OwnPanel.names("dev.chicken.rear.dev/dev.kb896.aiusage.rear.RearActivity#3", release))
        assertFalse(OwnPanel.names("dev.chicken.rear/dev.kb896.aiusage.rear.RearActivity#3", dev))
    }

    @Test fun `a longer package ending in ours is not ours`() {
        assertFalse(OwnPanel.names("x.dev.chicken.rear/dev.kb896.aiusage.rear.RearActivity#3", release))
    }

    @Test fun `our package with another activity is not the panel`() {
        assertFalse(OwnPanel.names("dev.chicken.rear/dev.kb896.aiusage.ui.MainActivity#3 RearActivity", release))
    }

    @Test fun `the other build is the family less ourselves`() {
        assertTrue(OwnPanel.isOtherBuild(dev, release))
        assertTrue(OwnPanel.isOtherBuild(release, dev))
        assertFalse(OwnPanel.isOtherBuild(release, release))
        assertFalse(OwnPanel.isOtherBuild("dev.chicken.rearx", release))
        assertFalse(OwnPanel.isOtherBuild("org.mozilla.firefox", release))
    }
}
