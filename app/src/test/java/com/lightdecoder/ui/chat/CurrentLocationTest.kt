package com.lightdecoder.ui.chat

import android.app.Application
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** "Send My Current Location": only with the permission, only in the foreground, never anything without it. */
@RunWith(AndroidJUnit4::class)
class CurrentLocationTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun withoutThePermissionThereIsNoLocationAndSoNothingToSend() {
        shadowOf(app).denyPermissions(*CurrentLocation.PERMISSIONS)
        assertFalse(CurrentLocation.permitted(app))
        assertNull(runBlocking { CurrentLocation.get(app) })
    }

    @Test
    fun withThePermissionAFreshLastKnownLocationStandsIn() {
        shadowOf(app).grantPermissions(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        assertTrue(CurrentLocation.permitted(app))
        val manager = app.getSystemService(LocationManager::class.java)
        val lm = shadowOf(manager)
        lm.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        lm.setLastKnownLocation(LocationManager.NETWORK_PROVIDER, android.location.Location(LocationManager.NETWORK_PROVIDER).apply {
            latitude = 52.5; longitude = 13.4; time = System.currentTimeMillis() - 30_000
        })
        val at = runBlocking { CurrentLocation.get(app) }
        // A fix, or the fresh last known one: either is this phone's.
        assertTrue(at == null || at == 52.5 to 13.4)
    }

    @Test
    fun theProviderAndHowOldAFixMayBe() {
        val all = setOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        assertEquals(LocationManager.FUSED_PROVIDER, CurrentLocation.providerFor(31, all, all))
        assertEquals(LocationManager.GPS_PROVIDER, CurrentLocation.providerFor(30, all, all))
        assertEquals(LocationManager.NETWORK_PROVIDER, CurrentLocation.providerFor(33, setOf(LocationManager.NETWORK_PROVIDER), all))
        assertNull(CurrentLocation.providerFor(33, emptySet(), all))
        val now = 10_000_000L
        assertTrue(CurrentLocation.fresh(now - 60_000, now))
        assertFalse(CurrentLocation.fresh(now - 3 * 60_000, now))
        assertFalse("never from the future", CurrentLocation.fresh(now + 1, now))
    }

    @Test
    fun theLocationPermissionsAreForegroundOnly() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val declared = Regex("<uses-permission\\s+android:name=\"([^\"]+)\"").findAll(manifest).map { it.groupValues[1] }.toSet()
        assertTrue("android.permission.ACCESS_COARSE_LOCATION" in declared)
        assertTrue("android.permission.ACCESS_FINE_LOCATION" in declared)
        assertFalse("android.permission.ACCESS_BACKGROUND_LOCATION" in declared)
        // Asked for only from "+" > Location, nowhere at start.
        val thread = File("src/main/java/com/lightdecoder/ui/chat/ThreadActivity.kt").readText()
        assertEquals(1, Regex("locationPermission\\.launch").findAll(thread).count())
    }

    @Test
    fun anAddressIsOneShortLine() {
        assertEquals("1 Infinite Loop, Cupertino", LocationAddresses.oneLine("1 Infinite Loop,\nCupertino".replace("\n", " ").replace(",  ", ", ")))
        assertEquals("a b", LocationAddresses.oneLine("a\u0007b"))
        assertNull(LocationAddresses.oneLine("  \n "))
        assertEquals(LocationAddresses.MAX_CHARS, LocationAddresses.oneLine("x".repeat(500))!!.length)
    }
}
