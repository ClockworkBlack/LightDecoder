package com.lightdecoder.imessage.facetime

import com.lightdecoder.imessage.direct.DirectProcess
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * FaceTime's components as SPEC2 B2 declares them: the call screen floats in picture-in-picture (and
 * stays in the ":imessage" process, unexported); the FaceTime list is the main process's, unexported;
 * nothing of FaceTime is exported.
 */
class FaceTimeManifestTest {

    private val android = "http://schemas.android.com/apk/res/android"

    private fun components(): List<Element> {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val application = factory.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml")).getElementsByTagName("application").item(0) as Element
        val nodes = application.childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    private fun named(name: String): Element = components().firstOrNull { it.getAttributeNS(android, "name") == name }.also { assertNotNull("$name is declared", it) }!!

    @Test
    fun theCallScreenFloatsInPictureInPictureAndStaysUnexportedInTheIMessageProcess() {
        val screen = named(".ui.imessage.FaceTimeCallActivity")
        assertEquals("true", screen.getAttributeNS(android, "supportsPictureInPicture"))
        assertEquals("false", screen.getAttributeNS(android, "exported"))
        assertEquals(DirectProcess.SUFFIX, screen.getAttributeNS(android, "process"))
        assertEquals("singleInstance", screen.getAttributeNS(android, "launchMode"))
        assertEquals(0, screen.getElementsByTagName("intent-filter").length)
        val receiver = named(".imessage.facetime.FaceTimeActionReceiver")
        assertEquals("false", receiver.getAttributeNS(android, "exported"))
        assertEquals(0, receiver.getElementsByTagName("intent-filter").length)
    }

    @Test
    fun theFaceTimeListIsTheMainProcessesAndNotExported() {
        val list = named(".ui.chat.CallsActivity")
        assertEquals("false", list.getAttributeNS(android, "exported"))
        assertTrue("the main process", list.getAttributeNS(android, "process").isEmpty())
        assertEquals(0, list.getElementsByTagName("intent-filter").length)
    }

    @Test
    fun nothingOfFaceTimeIsExported() {
        val faceTime = components().filter { it.getAttributeNS(android, "name").let { n -> n.contains("FaceTime") || n.contains("facetime") || n.endsWith("CallsActivity") } }
        assertTrue(faceTime.size >= 4)
        for (c in faceTime) assertEquals(c.getAttributeNS(android, "name"), "false", c.getAttributeNS(android, "exported"))
    }
}
