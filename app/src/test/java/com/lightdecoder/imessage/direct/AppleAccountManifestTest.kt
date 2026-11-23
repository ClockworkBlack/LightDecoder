package com.lightdecoder.imessage.direct

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The Apple Account's sign-in screen is declared as the spec has it (not exported, in the ":imessage"
 * process, never over the lock screen), and nothing of LightDecoder calls a library function the
 * sign-in must never use (SPEC2 S8).
 */
class AppleAccountManifestTest {

    private val android = "http://schemas.android.com/apk/res/android"

    private fun components(): List<Element> {
        // Unit tests run in the module's folder.
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val application = factory.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml")).getElementsByTagName("application").item(0) as Element
        val nodes = application.childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    @Test
    fun theSignInScreenIsNotExportedRunsInTheIMessageProcessAndNotOverTheLockScreen() {
        val screen = components().firstOrNull { it.getAttributeNS(android, "name") == ".ui.imessage.AppleAccountActivity" }
        assertNotNull("the sign-in screen is declared", screen)
        assertEquals("false", screen!!.getAttributeNS(android, "exported"))
        assertEquals(DirectProcess.SUFFIX, screen.getAttributeNS(android, "process"))
        assertTrue("not over the lock screen", screen.getAttributeNS(android, "showWhenLocked").let { it.isEmpty() || it == "false" })
        assertTrue(screen.getAttributeNS(android, "turnScreenOn").let { it.isEmpty() || it == "false" })
        assertEquals("no intent filter", 0, screen.getElementsByTagName("intent-filter").length)
    }

    /**
     * The engine itself cannot run in a test (the library is native): what it hands the account is
     * checked in its source instead, and each of those hooks with no account in AppleAccountTest (S1).
     * So a start, a registration, Apple's events and every send reach the account only here.
     */
    @Test
    fun theEngineHandsTheAccountOnlyItsStartItsNewDeviceAndItsNewSession() {
        val source = File("src/main/java/com/lightdecoder/imessage/direct/DirectEngine.kt").readLines()
        val code = source.filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }.joinToString("\n")
        val uses = Regex("(?<![A-Za-z0-9_.])account\\.([A-Za-z]+)").findAll(code).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("atStartLocked", "sessionUpLocked", "newDeviceLocked", "sessionReplacedLocked"), uses)
        // §4.4: the start's table right before the session is built from disk; its state read once it is up.
        val start = code.substringAfter("fun start()").substringBefore("private fun initLibrary")
        val restore = start.indexOf("restoreSession(")
        assertTrue(start, restore > 0)
        assertTrue(start, start.indexOf("account.atStartLocked()") in 0 until restore)
        assertTrue(start, start.indexOf("account.sessionUpLocked()") > restore)
        // S10: the one place a new identity is set is inside newDeviceLocked, which wipes the account first.
        assertEquals(1, Regex("setIdentity\\(").findAll(code).count())
        assertTrue(code.contains("account.newDeviceLocked { uniffi.rust_bridge.setIdentity(statePath, config, identity) }"))
        // A pending restart made needless only once the new session is in place.
        val register = code.substringAfter("private suspend fun doRegister(").substringBefore("private fun keepPhoneUser")
        assertTrue(register, register.indexOf("account.sessionReplacedLocked()") > register.indexOf("session = started"))
        // The session is started with no account, as in 0.5.72 (mode A, S1).
        assertTrue(register.contains("statePath, config, conn, anisette, null, result.users ?: users, identity, null, pipeline!!,"))
    }

    @Test
    fun nothingCallsWhatTheSignInMustNeverUse() {
        val root = File("src/main/java/com/lightdecoder")
        assertTrue(root.isDirectory)
        val call = Regex("(?<![A-Za-z0-9_])(" + FakeAccountBridge.NEVER.joinToString("|") + ")\\s*\\(")
        val found = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                val code = line.substringBefore("//").trimStart()
                if (code.startsWith("*") || code.startsWith("/*")) null else call.find(code)?.let { "${f.name}:${i + 1}: ${it.value}" }
            }
        }.toList()
        assertTrue(found.joinToString("\n"), found.isEmpty())
    }
}
