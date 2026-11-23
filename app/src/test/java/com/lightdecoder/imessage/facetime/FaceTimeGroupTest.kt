package com.lightdecoder.imessage.facetime

import android.graphics.PixelFormat
import android.media.ImageReader
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightdecoder.imessage.facetime.FakeBridge.Companion.ALICE
import com.lightdecoder.imessage.facetime.FakeBridge.Companion.BOB
import com.lightdecoder.imessage.facetime.FakeBridge.Companion.ME
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.rust_bridge.FaceTimeEvent
import uniffi.rust_bridge.FtMember

/**
 * The plain parts of group FaceTime and of what the call screen shows (SPEC2 B2): where tiles go,
 * the picture-in-picture window's shape, who is named in a call (only whom Apple vouches for), the
 * events' participant, the views the library draws into, and where a group call's row goes.
 */
@RunWith(AndroidJUnit4::class)
class FaceTimeGroupTest {

    private val readers = ArrayList<ImageReader>()

    @After
    fun tearDown() = readers.forEach { it.close() }

    private fun surface(): Surface = ImageReader.newInstance(64, 64, PixelFormat.RGBA_8888, 2).also { readers += it }.surface

    // --- tiles ------------------------------------------------------------------------------------

    @Test
    fun oneTileFillsTheScreenAndMoreAreTheBiggestSquaresThatFit() {
        assertEquals(listOf(TileLayout.Slot(0, 0, 1080, 1920)), TileLayout.slots(1, 1080, 1920, 10, 10))
        assertTrue(TileLayout.slots(0, 1080, 1920, 10, 10).isEmpty())
        assertTrue(TileLayout.slots(3, 0, 1920, 10, 10).isEmpty())
        for (n in 2..9) {
            val slots = TileLayout.slots(n, 1080, 1920, 10, 10)
            assertEquals(n, slots.size)
            assertTrue("$n: squares", slots.all { it.width == it.height && it.width > 0 })
            assertEquals("$n: all one size", 1, slots.map { it.width }.toSet().size)
            assertTrue("$n: inside", slots.all { it.x >= 0 && it.y >= 0 && it.x + it.width <= 1080 && it.y + it.height <= 1920 })
            for (a in slots) for (b in slots) if (a !== b) {
                assertFalse("$n: no two overlap", a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height)
            }
        }
        // An upright screen: two one above the other (wider ones), not side by side.
        val two = TileLayout.slots(2, 1080, 1920, 10, 10)
        assertEquals(two[0].x, two[1].x)
        assertEquals(945, two[0].width)
        // Four: two by two, rows centred.
        val four = TileLayout.slots(4, 1080, 1920, 10, 10)
        assertEquals(2, four.map { it.x }.toSet().size)
        assertEquals(2, four.map { it.y }.toSet().size)
        // Three on a wide screen: in a row.
        assertEquals(1, TileLayout.slots(3, 1920, 600, 10, 10).map { it.y }.toSet().size)
        // A row that is not full is centred.
        val five = TileLayout.slots(5, 1080, 1920, 10, 10)
        val last = five.last()
        assertEquals((1080 - last.width) / 2, last.x)
        // The decoded pictures' cap.
        assertEquals(6, TileLayout.MAX_CAMERA_TILES)
        assertEquals(1, TileLayout.MAX_SCREEN_TILES)
    }

    @Test
    fun aTappedTileFillsTheScreenWithTheOthersSmallAlongTheTop() {
        val slots = TileLayout.focused(4, 2, 1080, 1920, thumb = 200, gap = 10, top = 300)
        assertEquals(TileLayout.Slot(0, 0, 1080, 1920), slots[2])
        assertEquals(listOf(TileLayout.Slot(10, 300, 200, 200), TileLayout.Slot(220, 300, 200, 200), TileLayout.Slot(430, 300, 200, 200)), listOf(slots[0], slots[1], slots[3]))
        // More than fit in a row go on in the next.
        val many = TileLayout.focused(8, 0, 500, 1920, thumb = 200, gap = 10, top = 0)
        assertEquals(210, many[3].y)
        // No such tile: the grid.
        assertEquals(TileLayout.slots(3, 1080, 1920, 10, 10), TileLayout.focused(3, 7, 1080, 1920, 200, 10, 300, margin = 10))
        // The user's own picture settles in the nearest corner, clear of the buttons.
        assertEquals(16 to 72, TileLayout.nearestCorner(100f, 200f, 1080, 1920, 300, 450, margin = 16, top = 72, bottom = 400))
        assertEquals(1080 - 300 - 16 to 1920 - 450 - 400, TileLayout.nearestCorner(700f, 1500f, 1080, 1920, 300, 450, margin = 16, top = 72, bottom = 400))
        assertEquals(1 to 1, TileLayout.grid(1))
        assertEquals(2 to 1, TileLayout.grid(2))
        assertEquals(2 to 2, TileLayout.grid(4))
        assertEquals(3 to 2, TileLayout.grid(5))
    }

    // --- picture in picture ------------------------------------------------------------------------

    @Test
    fun theWindowsShapeIsThePicturesWithinWhatAndroidTakes() {
        fun ratio(w: Int, h: Int, tiles: Int = 1) = PipParams.aspect(w, h, tiles).toDouble() / PipParams.DENOMINATOR
        assertEquals(1920.0 / 1080, ratio(1920, 1080), 0.001)
        assertEquals(1080.0 / 1920, ratio(1080, 1920), 0.001)
        assertEquals("nothing known: 9:16", 9.0 / 16, ratio(0, 0), 0.001)
        assertEquals(9.0 / 16, ratio(-5, 100), 0.001)
        // Far too wide or too tall: as wide or tall as Android takes, never past it.
        assertTrue(ratio(8000, 100) <= PipParams.MAX_RATIO)
        assertEquals(PipParams.MAX_RATIO, ratio(8000, 100), 0.0001)
        assertTrue(ratio(100, 8000) >= 1 / PipParams.MAX_RATIO)
        assertEquals(1 / PipParams.MAX_RATIO, ratio(100, 8000), 0.001)
        // Several tiles: the grid's shape.
        assertEquals(1.0, ratio(1080, 1920, tiles = 4), 0.001)
        assertEquals(2.0, ratio(1080, 1920, tiles = 2), 0.001)
        assertEquals(1.5, ratio(0, 0, tiles = 6), 0.001)
    }

    // --- who is named -------------------------------------------------------------------------------

    @Test
    fun onlyWhomAppleVouchesForIsNamedOrLookedUp() {
        val inCall = listOf(InCall(7, ALICE), InCall(8, MOM), InCall(9, "temp:abc"), InCall(10, "nonsense"), InCall(11, MOM))
        // Participant 7 joined as Alice; 8 says it is Mom (any member can relabel anyone); 11 joined as Bob but is reported as Mom.
        val labels = CallLabels.of(inCall, mapOf(7L to ALICE, 11L to BOB))
        assertEquals(
            listOf(
                CallLabels.Label(7, CallLabels.Kind.VERIFIED, ALICE, ALICE),
                CallLabels.Label(8, CallLabels.Kind.NOT_VERIFIED, MOM, MOM),
                CallLabels.Label(9, CallLabels.Kind.GUEST, null, "temp:abc"),
                CallLabels.Label(10, CallLabels.Kind.NOT_VERIFIED, null, "nonsense"),
                CallLabels.Label(11, CallLabels.Kind.VERIFIED, BOB, MOM),
            ),
            labels,
        )
        // Looked up: Alice and Bob, each once, never Mom (whom only the call's word names).
        assertEquals(listOf(ALICE, BOB), CallLabels.lookups(labels, emptySet()))
        assertEquals(listOf(BOB), CallLabels.lookups(labels, setOf(CallIds.key(ALICE))))
        assertTrue(CallLabels.lookups(CallLabels.of(inCall, emptyMap()), emptySet()).isEmpty())
        // What other people's phones write is shown on one line, nothing turned around or hidden.
        assertEquals("Mom", CallIds.label("‮Mom​\n"))
        assertEquals("tel:+15550000", CallIds.reported(" tel:+1555‮0000\u0000 "))
        assertTrue(CallIds.isGuest("temp:x"))
        assertFalse(CallIds.isGuest(ALICE))
    }

    @Test
    fun theEventsCarryWhoJoinedWithWhichIdAndWhoWasTakenOut() {
        val join = CallEvent.of(FaceTimeEvent.JoinEvent(GUID, 42uL, ALICE, true)) as CallEvent.Join
        assertEquals(42L, join.participant)
        assertEquals("the id is not part of what makes two joins the same", CallEvent.Join(GUID, ALICE, ring = true), join)
        assertEquals(CallEvent.Removed(GUID, listOf(ME)), CallEvent.of(FaceTimeEvent.RemoveMembers(GUID, listOf(FtMember(null, ME), FtMember("x", "temp:x")))))
        assertNull("naming no address: nothing", CallEvent.of(FaceTimeEvent.RemoveMembers(GUID, listOf(FtMember(null, "temp:x")))))
        assertNull(CallEvent.of(FaceTimeEvent.RemoveMembers("not-a-call", listOf(FtMember(null, ME)))))
        assertEquals(CallEvent.MAX_MEMBERS, (CallEvent.of(FaceTimeEvent.RemoveMembers(GUID, List(500) { FtMember(null, ME) })) as CallEvent.Removed).handles.size)
    }

    // --- the views the library draws into -------------------------------------------------------------

    @Test
    fun aSharedScreenGetsItsOwnViewAndASecondCameraNone() {
        val surfaces = CallSurfaces()
        val remote = surface()
        val screen = surface()
        surfaces.setRemote(remote)
        assertNull("no view for it yet", surfaces.forStream(7, CallSurfaces.STREAM_SCREEN))
        surfaces.setScreen(screen)
        assertSame(screen, surfaces.forStream(7, CallSurfaces.STREAM_SCREEN))
        assertTrue(surfaces.showsScreen(7, CallSurfaces.STREAM_SCREEN))
        assertNull("one shared screen at a time", surfaces.forStream(8, CallSurfaces.STREAM_SCREEN))
        assertSame("the camera keeps its own view", remote, surfaces.forStream(7, CallSurfaces.STREAM_CAMERA))
        assertNull("a second camera is never shown", surfaces.forStream(7, CallSurfaces.GROUP_SECOND_CAMERA))
        assertNull(surfaces.forStream(7, 99))
        // The screen stops: the view is free for another.
        surfaces.removed(7, intArrayOf(CallSurfaces.STREAM_SCREEN))
        assertFalse(surfaces.showsScreen(7, CallSurfaces.STREAM_SCREEN))
        assertSame(screen, surfaces.forStream(8, CallSurfaces.STREAM_SCREEN))
        // What the library asked for, for the screen to make views: the stream gone is no longer.
        assertTrue(surfaces.requested.value.contains(8L to CallSurfaces.STREAM_SCREEN))
        assertFalse(surfaces.requested.value.contains(7L to CallSurfaces.STREAM_SCREEN))
    }

    @Test
    fun aGroupCallsCamerasGoIntoTilesAndOnlySixAreDrawn() {
        val surfaces = CallSurfaces()
        surfaces.setRemote(surface())
        surfaces.newCall(group = true)
        // Asked for, before the screen made a tile: nothing yet, and said as asked for.
        for (p in 1L..8L) assertNull(surfaces.forStream(p, CallSurfaces.STREAM_CAMERA))
        assertEquals((1L..8L).map { it to CallSurfaces.STREAM_CAMERA }, surfaces.requested.value)
        // The screen makes tiles (even past the cap): six are drawn, the seventh gets none.
        val tiles = (1L..8L).associateWith { surface() }
        tiles.forEach { (p, s) -> surfaces.setTile(p, s) }
        for (p in 1L..6L) assertSame("tile $p", tiles[p], surfaces.forStream(p, CallSurfaces.STREAM_CAMERA))
        assertNull("the seventh camera tile", surfaces.forStream(7, CallSurfaces.STREAM_CAMERA))
        assertNull("one stream per person", surfaces.forStream(1, CallSurfaces.STREAM_CAMERA_ALTERNATE))
        // Never the one big view in a group call.
        assertFalse(surfaces.showing)
        // One goes: another may be drawn.
        surfaces.removed(3, intArrayOf(CallSurfaces.STREAM_CAMERA))
        assertFalse(surfaces.showsTile(3, CallSurfaces.STREAM_CAMERA))
        assertSame(tiles[7], surfaces.forStream(7, CallSurfaces.STREAM_CAMERA))
        // Only so many asked-for streams are remembered.
        for (p in 100L..300L) surfaces.forStream(p, CallSurfaces.STREAM_CAMERA)
        assertEquals(CallSurfaces.MAX_REQUESTED, surfaces.requested.value.size)
        // A new call starts again.
        surfaces.newCall(group = false)
        assertTrue(surfaces.requested.value.isEmpty())
        assertFalse(surfaces.tilesOnly)
    }

    // --- where a group call's row goes ------------------------------------------------------------------

    @Test
    fun aGroupCallsRowGoesInAGroupChatOnlyOfExactlyItsPeopleWithItsCallerAmongThem() {
        val same: (String, String) -> Boolean = { a, b -> a.removePrefix("tel:") == b.removePrefix("tel:") }
        val hikers = "imsg:group:H" to listOf("+15551234567", "+15557654321")
        val family = "imsg:group:F" to listOf("+15557654321", "+15553334444")
        val bigger = "imsg:group:B" to listOf("+15551234567", "+15557654321", "+15553334444")
        val candidates = listOf(family, bigger, hikers)
        assertEquals("imsg:group:H", CallRows.groupRowChat(ALICE, listOf(ALICE, BOB), candidates, same))
        assertEquals("imsg:group:B", CallRows.groupRowChat(ALICE, listOf(BOB, ALICE, CAROL), candidates, same))
        // A stranger naming a chat's people: not one of them, so not there.
        assertNull(CallRows.groupRowChat(MALLORY, listOf(MALLORY, BOB, CAROL), candidates, same))
        assertNull("the caller must be in the list too", CallRows.groupRowChat(MALLORY, listOf(BOB, CAROL), candidates, same))
        // No chat of exactly them: none (their own chat then).
        assertNull(CallRows.groupRowChat(ALICE, listOf(ALICE, CAROL), candidates, same))
        assertNull("one person is no group", CallRows.groupRowChat(ALICE, listOf(ALICE), candidates, same))
    }

    private companion object {
        const val GUID = "AAAAAAAA-0000-4000-8000-000000000001"
        const val MOM = "tel:+15550001111"
        const val CAROL = "tel:+15553334444"
        const val MALLORY = "tel:+15559990000"
    }
}
