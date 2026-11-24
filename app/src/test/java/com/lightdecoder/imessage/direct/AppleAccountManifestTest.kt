package com.lightdecoder.imessage.direct

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** Every call of one of [names] in LightDecoder's own sources (comments left out), as "File.kt:line: call". */
    private fun callsOf(names: List<String>): List<String> {
        val root = File("src/main/java/com/lightdecoder")
        assertTrue(root.isDirectory)
        val call = Regex("(?<![A-Za-z0-9_])(" + names.joinToString("|") + ")\\s*\\(")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                val code = line.substringBefore("//").trimStart()
                if (code.startsWith("*") || code.startsWith("/*")) null else call.findAll(code).joinToString { it.value }.takeIf { it.isNotEmpty() }?.let { "${f.name}:${i + 1}: $it" }
            }
        }.toList()
    }

    /** The scan covers every source file, the Apple Account's features (B6) among them. */
    @Test
    fun theScanCoversTheFeaturesFiles() {
        val names = File("src/main/java/com/lightdecoder").walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.name }.toSet()
        for (f in listOf("AccountFeatures.kt", "FeatureBridge.kt", "Profiles.kt", "FocusStatus.kt", "FocusSharer.kt", "CloudImport.kt", "CloudMapping.kt", "KeychainJoin.kt", "SharedProfiles.kt", "FocusStates.kt", "AppleFeaturePages.kt")) {
            assertTrue(f, f in names)
        }
    }

    @Test
    fun nothingCallsWhatTheSignInMustNeverUse() {
        val found = callsOf(FakeAccountBridge.NEVER - FakeAccountBridge.JOIN_ONLY.toSet())
        assertTrue(found.joinToString("\n"), found.isEmpty())
    }

    /**
     * The keychain join (B6.5) is the one place `resetState` (always with `logout = false`: never a
     * sign-out at Apple) and `circleSetupClique` are called; nothing else ever calls them.
     */
    @Test
    fun onlyTheKeychainJoinResetsTheRegistrationAndJoinsTheKeychainAndNeverSignsOutAtApple() {
        val found = callsOf(FakeAccountBridge.JOIN_ONLY)
        assertTrue(found.joinToString("\n"), found.all { it.startsWith("KeychainJoin.kt:") })
        val resets = found.filter { "resetState(" in it }
        assertEquals(found.joinToString("\n"), 1, resets.size)
        assertEquals(1, found.count { "circleSetupClique(" in it })
        val source = File("src/main/java/com/lightdecoder/imessage/direct/KeychainJoin.kt").readText()
        assertTrue(source.contains("uniffi.rust_bridge.resetState(old, statePath, false, false)"))
        // The join is only by the approving device's voucher: never an escrow record of another device's.
        for (never in listOf("joinCliqueWithBottle(", "getBottles(", "resetClique(", "joinCliqueWithPreviousDevice(", "changeEscrowPassword(")) {
            assertFalse(never, source.contains(never))
        }
    }

    /** The import is read-only: of iCloud's Messages functions only the syncs and the attachments' download are called. */
    @Test
    fun messagesInIcloudIsOnlyRead() {
        val root = File("src/main/java/com/lightdecoder")
        val call = Regex("uniffi\\.rust_bridge\\.([a-z]+Cloud[A-Za-z]*)\\(")
        val used = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f -> call.findAll(f.readText()).map { it.groupValues[1] } }.toSet()
        assertEquals(setOf("syncCloudChats", "syncCloudMessages", "syncCloudAttachments", "downloadCloudAttachments"), used)
    }
}
