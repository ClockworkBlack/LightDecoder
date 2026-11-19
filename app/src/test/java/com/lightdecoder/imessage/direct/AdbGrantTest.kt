package com.lightdecoder.imessage.direct

import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** LightDecoder's ADB identity: a certificate Wireless debugging's TLS accepts (it only needs the key in it). */
class AdbGrantTest {

    @Test
    fun theCertificateIsSelfSignedForItsKeyAndValidNow() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val cert = AdbGrant.selfSigned(pair.public, pair.private) as X509Certificate
        cert.verify(pair.public)
        cert.checkValidity()
        assertEquals(pair.public, cert.publicKey)
        assertEquals(3, cert.version)
        assertTrue(cert.subjectX500Principal.name.contains("LightDecoder"))
    }

    @Test
    fun theOneCommandIsTheAppOp() {
        assertEquals("USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER", SimAuth.OP_SHELL_NAME)
    }
}
