package com.lightdecoder.imessage.direct

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.android.AdbMdns
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x509.Time
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator

/**
 * Allows [SimAuth] from the phone itself, with no other app and no computer: LightDecoder connects
 * to this phone's own Wireless debugging (Android 11+) as an ADB client (libadb-android, which
 * speaks ADB's pairing and TLS) and runs the one shell command
 * `appops set --uid <LightDecoder> USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER allow`. Nothing else is
 * ever run. The same job the separate Shizuku app does for other apps, built in.
 *
 * The user turns on Wireless debugging and taps "Pair device with pairing code"; [AdbPairService]
 * sees the pairing offer on the phone (mDNS) and asks for the six digits in a notification, because
 * Android closes the pairing offer when its screen is left.
 */
object AdbGrant {

    const val LOOPBACK = "127.0.0.1"

    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** LightDecoder's ADB identity (an RSA key and a self-signed certificate), kept in app storage. */
    private class Manager(context: Context) : AbsAdbConnectionManager() {
        private val key: PrivateKey
        private val cert: Certificate

        init {
            api = Build.VERSION.SDK_INT
            hostAddress = LOOPBACK
            val dir = File(context.filesDir, "imessage/adb").apply { mkdirs() }
            val keyFile = File(dir, "key.pk8")
            val certFile = File(dir, "cert.der")
            if (keyFile.isFile && certFile.isFile) {
                key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(certFile.readBytes()))
            } else {
                val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()
                key = pair.private
                cert = selfSigned(pair.public, pair.private)
                keyFile.writeBytes(key.encoded)
                certFile.writeBytes(cert.encoded)
            }
        }

        override fun getPrivateKey(): PrivateKey = key
        override fun getCertificate(): Certificate = cert
        override fun getDeviceName(): String = "LightDecoder"
    }

    /** A self-signed X.509 certificate for [public] (ADB's TLS only needs the key in it). */
    internal fun selfSigned(public: PublicKey, private: PrivateKey): Certificate {
        val name = X500Name("CN=LightDecoder")
        val algorithm = AlgorithmIdentifier(PKCSObjectIdentifiers.sha256WithRSAEncryption, DERNull.INSTANCE)
        val now = System.currentTimeMillis()
        val tbs = V3TBSCertificateGenerator().apply {
            setSerialNumber(ASN1Integer(BigInteger(63, SecureRandom()).add(BigInteger.ONE)))
            setSignature(algorithm)
            setIssuer(name)
            setSubject(name)
            setStartDate(Time(Date(now - 86_400_000L)))
            setEndDate(Time(Date(now + 20L * 365 * 86_400_000L)))
            setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(public.encoded))
        }.generateTBSCertificate()
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(private)
            update(tbs.getEncoded("DER"))
            sign()
        }
        val der = DERSequence(arrayOf(tbs, algorithm, DERBitString(signature))).getEncoded("DER")
        return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der))
    }

    @Volatile
    private var manager: Manager? = null

    private fun manager(context: Context): Manager =
        manager ?: synchronized(this) { manager ?: Manager(context.applicationContext).also { manager = it } }

    /** Pairs with this phone's Wireless debugging: [port] and [code] from its "Pair device with pairing code". */
    fun pair(context: Context, port: Int, code: String) {
        manager(context).pair(LOOPBACK, port, code.trim())
    }

    /** The port Wireless debugging listens on for paired clients, found on the phone itself (mDNS). Null if not found in time. */
    fun connectPort(context: Context, timeoutMs: Long = 15_000): Int? {
        val port = AtomicInteger(-1)
        val found = CountDownLatch(1)
        val mdns = AdbMdns(context, AdbMdns.SERVICE_TYPE_TLS_CONNECT) { address, p ->
            if (address != null && p > 0) {
                port.set(p)
                found.countDown()
            }
        }
        mdns.start()
        try {
            found.await(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            mdns.stop()
        }
        return port.get().takeIf { it > 0 }
    }

    /**
     * Connects (LightDecoder must have been paired) and allows SIM verification. Returns null when
     * Android now shows it allowed, else what went wrong.
     */
    fun allowSimAuth(context: Context): String? {
        val port = connectPort(context) ?: return "Wireless debugging was not found. Is it still on?"
        val m = manager(context)
        return try {
            if (!m.connect(LOOPBACK, port)) return "Wireless debugging refused the connection."
            val command = SimAuth.shellCommand(context).joinToString(" ")
            val output = m.openStream("shell:$command").use { stream ->
                val out = StringBuilder()
                val buf = ByteArray(1024)
                try {
                    while (true) {
                        val n = stream.read(buf, 0, buf.size)
                        if (n < 0) break
                        out.append(String(buf, 0, n))
                    }
                } catch (e: java.io.IOException) {
                    // The stream closes when the command ends.
                }
                out.toString().trim()
            }
            if (SimAuth.allowed(context)) null else output.ifEmpty { "The command ran, but Android does not show the permission as allowed." }
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        } finally {
            runCatching { m.disconnect() }
        }
    }
}
