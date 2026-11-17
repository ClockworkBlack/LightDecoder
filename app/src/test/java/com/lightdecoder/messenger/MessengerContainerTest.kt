package com.lightdecoder.messenger

import com.lightdecoder.discord.DiscordContainer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The web screen's container is only real if the manifest puts that screen,
 * and nothing else, in the process [MessengerContainer] names, apart from
 * every other tab's.
 */
class MessengerContainerTest {

    private val android = "http://schemas.android.com/apk/res/android"

    private fun components(): List<Element> {
        // Unit tests run in the module's folder.
        val file = File("src/main/AndroidManifest.xml")
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val application = factory.newDocumentBuilder().parse(file).getElementsByTagName("application").item(0) as Element
        val nodes = application.childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    private fun component(name: String): Element? = components().firstOrNull { it.getAttributeNS(android, "name") == name }

    @Test
    fun onlyTheMessengerProcessIsTheContainer() {
        assertTrue(MessengerContainer.isMessengerProcess("com.lightdecoder:messenger", "com.lightdecoder"))
        assertFalse("the main process", MessengerContainer.isMessengerProcess("com.lightdecoder", "com.lightdecoder"))
        assertFalse("the Discord tab's", MessengerContainer.isMessengerProcess("com.lightdecoder:discord", "com.lightdecoder"))
        assertFalse("another app's", MessengerContainer.isMessengerProcess("com.other:messenger", "com.lightdecoder"))
        assertFalse(MessengerContainer.isMessengerProcess(null, "com.lightdecoder"))
    }

    @Test
    fun itsProcessAndDataFolderAreNotAnotherTabs() {
        assertNotEquals(DiscordContainer.PROCESS_SUFFIX, MessengerContainer.PROCESS_SUFFIX)
        assertNotEquals(DiscordContainer.DATA_SUFFIX, MessengerContainer.DATA_SUFFIX)
        // The main process's folder is app_webview; a suffix makes it app_webview_<suffix>.
        assertNotEquals("", MessengerContainer.DATA_SUFFIX)
        assertTrue(MessengerContainer.DATA_SUFFIX.all { it.isLetterOrDigit() })
    }

    @Test
    fun theWebScreenRunsInItsOwnProcessAndIsNotExported() {
        val screen = component(".ui.messenger.MessengerWebActivity")
        assertNotNull("the web screen is declared", screen)
        assertEquals(MessengerContainer.PROCESS_SUFFIX, screen!!.getAttributeNS(android, "process"))
        assertEquals("false", screen.getAttributeNS(android, "exported"))
    }

    @Test
    fun nothingElseRunsInThatProcess() {
        val others = components().filter {
            it.getAttributeNS(android, "process") == MessengerContainer.PROCESS_SUFFIX &&
                it.getAttributeNS(android, "name") != ".ui.messenger.MessengerWebActivity"
        }
        assertEquals(emptyList<String>(), others.map { it.getAttributeNS(android, "name") })
    }

    @Test
    fun theInboxStaysInTheMainProcessAndIsNotExported() {
        for (name in listOf(".ui.messenger.MessengerActivity", ".ui.messenger.MessengerThreadActivity")) {
            val screen = component(name)
            assertNotNull("$name is declared", screen)
            assertNull("$name runs in the main process", screen!!.getAttributeNodeNS(android, "process"))
            assertEquals("false", screen.getAttributeNS(android, "exported"))
        }
    }

    @Test
    fun onlyTheSystemCanBindTheListener() {
        val listener = component(".messenger.MessengerNotificationListener")
        assertNotNull("the listener is declared", listener)
        assertEquals(
            "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
            listener!!.getAttributeNS(android, "permission"),
        )
        assertNull("it runs in the main process, where the inbox is", listener.getAttributeNodeNS(android, "process"))
    }
}
