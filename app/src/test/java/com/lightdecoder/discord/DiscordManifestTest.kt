package com.lightdecoder.discord

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The container is only real if the manifest puts the Discord screen in the
 * process [DiscordContainer] names, and nothing else in it.
 */
class DiscordManifestTest {

    private val android = "http://schemas.android.com/apk/res/android"

    private fun components(): List<Element> {
        // Unit tests run in the module's folder.
        val file = File("src/main/AndroidManifest.xml")
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val application = factory.newDocumentBuilder().parse(file).getElementsByTagName("application").item(0) as Element
        val nodes = application.childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    @Test
    fun theDiscordScreenRunsInItsOwnProcessAndIsNotExported() {
        val screen = components().firstOrNull { it.getAttributeNS(android, "name") == ".ui.discord.DiscordActivity" }
        assertNotNull("the Discord screen is declared", screen)
        assertEquals(DiscordContainer.PROCESS_SUFFIX, screen!!.getAttributeNS(android, "process"))
        assertEquals("false", screen.getAttributeNS(android, "exported"))
    }

    @Test
    fun onlyTheDiscordScreenAndItsWarmUpRunInTheDiscordProcess() {
        val inside = components().filter { it.getAttributeNS(android, "process") == DiscordContainer.PROCESS_SUFFIX }
        assertEquals(
            setOf(".ui.discord.DiscordActivity", ".discord.DiscordWarmup"),
            inside.map { it.getAttributeNS(android, "name") }.toSet(),
        )
        val warmup = inside.first { it.getAttributeNS(android, "name") == ".discord.DiscordWarmup" }
        assertEquals("nothing outside the app can start it", "false", warmup.getAttributeNS(android, "exported"))
    }

    @Test
    fun theOtherScreensStayInTheMainProcess() {
        val conversations = components().first { it.getAttributeNS(android, "name") == ".ui.chat.ConversationsActivity" }
        assertNull(conversations.getAttributeNodeNS(android, "process"))
    }
}
