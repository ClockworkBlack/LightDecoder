package com.lightdecoder.imessage.facetime

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The names the iMessage library binds by: `Java_com_localob_messaging_app_facetime_FaceTimeInCallService_*`
 * for the natives, and the callbacks and the field it looks up by name and JNI signature. A wrong
 * name fails only on a phone, at the first call; a wrong parameter list does not even fail (these JNI
 * names carry no signature: the library would read garbage). So every one is pinned here, by
 * reflection alone: nothing of the library is loaded.
 */
class FaceTimeContractTest {

    private val type = Class.forName(CLASS)

    /** A method's JNI descriptor, as `GetMethodID` is given it. */
    private fun descriptor(m: java.lang.reflect.Method): String =
        m.parameterTypes.joinToString("", "(", ")") { jni(it) } + jni(m.returnType)

    private fun jni(c: Class<*>): String = when {
        c == Void.TYPE -> "V"
        c == java.lang.Boolean.TYPE -> "Z"
        c == java.lang.Integer.TYPE -> "I"
        c == java.lang.Long.TYPE -> "J"
        c.isArray -> "[" + jni(c.componentType!!)
        else -> "L" + c.name.replace('.', '/') + ";"
    }

    private fun only(name: String): java.lang.reflect.Method {
        val named = type.declaredMethods.filter { it.name == name }
        assertEquals("exactly one method named $name (JNI binds by name alone)", 1, named.size)
        return named.single()
    }

    @Test
    fun theClassHasTheExactNameTheLibraryExportsItsFunctionsFor() {
        assertEquals("com.localob.messaging.app.facetime.FaceTimeInCallService", type.name)
        assertTrue("public, so a class of the app can make one", Modifier.isPublic(type.modifiers))
        // No static initializer may load the library by name: imported files are not on the library path.
        assertFalse("not an Android Service here", android.app.Service::class.java.isAssignableFrom(type))
    }

    @Test
    fun theElevenNativesAreInstanceNativeMethodsWithTheLibrarysParameterLists() {
        for ((name, wanted) in NATIVES) {
            val m = only(name)
            assertTrue("$name is native", Modifier.isNative(m.modifiers))
            assertFalse("$name is an instance method (the library keeps the object it was called on)", Modifier.isStatic(m.modifiers))
            assertEquals(name, wanted, descriptor(m))
        }
        val natives = type.declaredMethods.filter { Modifier.isNative(it.modifiers) }.map { it.name }.sorted()
        assertEquals("exactly the library's eleven", NATIVES.keys.sorted(), natives)
    }

    @Test
    fun theEightCallbacksHaveTheNamesAndSignaturesTheLibraryLooksUp() {
        for ((name, wanted) in CALLBACKS) {
            val m = only(name)
            assertFalse("$name is the app's, not native", Modifier.isNative(m.modifiers))
            assertFalse("$name is called on the object", Modifier.isStatic(m.modifiers))
            assertFalse("$name is not a mangled or bridge method", m.isSynthetic || m.isBridge)
            assertEquals(name, wanted, descriptor(m))
        }
        assertEquals(8, CALLBACKS.size)
    }

    @Test
    fun isVideoIsAnInstanceBooleanFieldTheLibraryCanWrite() {
        val field = type.declaredFields.single { it.name == "isVideo" }
        assertEquals(java.lang.Boolean.TYPE, field.type)
        assertFalse("an instance field", Modifier.isStatic(field.modifiers))
        assertFalse("written by createAvc", Modifier.isFinal(field.modifiers))
    }

    @Test
    fun nothingACallbackThrowsReachesTheLibrary() {
        val throwing = object : com.localob.messaging.app.facetime.FaceTimeInCallService.Host {
            override fun buildSurface(participant: Long, stream: Int): android.view.Surface? = throw IllegalStateException("x")
            override fun streamsRemoved(participant: Long, streams: IntArray) = throw IllegalStateException("x")
            override fun videoSize(participant: Long, stream: Int, width: Int, height: Int) = throw IllegalStateException("x")
            override fun streamRotation(participant: Long, stream: Int, rotation: Int) = throw IllegalStateException("x")
            override fun participants(ids: LongArray, handles: Array<String?>) = throw OutOfMemoryError("x")
            override fun encoderSurface(surface: android.view.Surface, group: Int) = throw IllegalStateException("x")
            override fun systemAudio(sampleRate: Int): android.media.AudioRecord? = throw IllegalStateException("x")
            override fun failed() = throw StackOverflowError("x")
        }
        val service = com.localob.messaging.app.facetime.FaceTimeInCallService(throwing)
        assertNotNull(service)
        assertEquals(null, service.buildSurface(1, 1))
        service.streamsRemoved(1, intArrayOf(1))
        // What the library hands over may be null: taken as nothing, never thrown back.
        service.streamsRemoved(1, null)
        service.setParticipantVideoSize(1, 1, 640, 480)
        service.setStreamRotation(1, 1, 0)
        service.updateParticipants(longArrayOf(1), arrayOf("tel:+15551234567"))
        service.updateParticipants(null, null)
        service.cameraEncodeSurface(null, 1)
        assertEquals(null, service.systemAudioRecord(32_000))
        service.callFailed()
    }

    companion object {
        const val CLASS = "com.localob.messaging.app.facetime.FaceTimeInCallService"

        /** The library's exported JNI functions (`nm -D`), with the parameter lists OpenBubbles 2.0.0 declares them with. */
        val NATIVES = mapOf(
            "createAvc" to "(Ljava/lang/String;J)J",
            "destroyAvc" to "(J)V",
            "enableCamera" to "(JZI)V",
            "enableCamera2" to "(JZIII)V",
            "enableMicrophone" to "(JZ)V",
            "enableScreenShare" to "(JZII)V",
            "refreshSurfaces" to "(J)V",
            "updateCamera2Orientation" to "(JI)V",
            "updateCaptureMode" to "(JI)V",
            "updateOrientation" to "(JI)V",
            "updateScreenOrientation" to "(JI)V",
        )

        /** The names and signatures in the library's read-only data, next to its FaceTime messages. */
        val CALLBACKS = mapOf(
            "buildSurface" to "(JI)Landroid/view/Surface;",
            "streamsRemoved" to "(J[I)V",
            "setParticipantVideoSize" to "(JIII)V",
            "setStreamRotation" to "(JII)V",
            "updateParticipants" to "([J[Ljava/lang/String;)V",
            "cameraEncodeSurface" to "(Landroid/view/Surface;I)V",
            "systemAudioRecord" to "(I)Landroid/media/AudioRecord;",
            "callFailed" to "()V",
        )
    }
}
