package com.lightdecoder.imessage.facetime

import com.lightdecoder.imessage.direct.DirectProcess
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /**
     * Nothing of FaceTime is exported, but for the ConnectionService Android requires to be (SPEC2 B3,
     * an intentional change to this test's rule): only Telecom can bind that one.
     */
    @Test
    fun nothingOfFaceTimeIsExportedButTheConnectionServiceTelecomAloneCanBind() {
        val faceTime = components().filter { it.getAttributeNS(android, "name").let { n -> n.contains("FaceTime") || n.contains("facetime") || n.endsWith("CallsActivity") } }
        assertTrue(faceTime.size >= 5)
        for (c in faceTime) {
            val name = c.getAttributeNS(android, "name")
            if (name == CONNECTION_SERVICE) continue
            assertEquals(name, "false", c.getAttributeNS(android, "exported"))
        }
    }

    @Test
    fun theConnectionServiceIsExportedOnlyUnderTelecomsPermissionInTheIMessageProcess() {
        val service = named(CONNECTION_SERVICE)
        assertEquals("service", service.tagName)
        assertEquals("true", service.getAttributeNS(android, "exported"))
        assertEquals("android.permission.BIND_TELECOM_CONNECTION_SERVICE", service.getAttributeNS(android, "permission"))
        assertEquals(DirectProcess.SUFFIX, service.getAttributeNS(android, "process"))
        val actions = service.getElementsByTagName("action")
        assertEquals(1, actions.length)
        assertEquals("android.telecom.ConnectionService", (actions.item(0) as Element).getAttributeNS(android, "name"))
        // Every other exported component of the app is not FaceTime's.
        val exported = components().filter { it.getAttributeNS(android, "exported") == "true" }.map { it.getAttributeNS(android, "name") }
        assertTrue(exported.none { it != CONNECTION_SERVICE && (it.contains("facetime") || it.contains("FaceTime")) })
    }

    @Test
    fun blockedContactsIsTheMainProcessesAndNotExported() {
        val screen = named(".ui.chat.BlockedContactsActivity")
        assertEquals("false", screen.getAttributeNS(android, "exported"))
        assertTrue("the main process", screen.getAttributeNS(android, "process").isEmpty())
        assertEquals(0, screen.getElementsByTagName("intent-filter").length)
    }

    @Test
    fun noBluetoothPermissionIsAsked() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("android.permission.BLUETOOTH_CONNECT"))
        assertTrue(manifest.contains("android.permission.MANAGE_OWN_CALLS"))
    }

    private companion object {
        const val CONNECTION_SERVICE = ".imessage.facetime.FaceTimeConnectionService"
    }
}
