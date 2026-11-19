package com.lightdecoder.imessage.direct

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.WrappedKeyEntry
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.interfaces.ECKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import uniffi.rust_bridge.EcCurve
import uniffi.rust_bridge.EncryptMode
import uniffi.rust_bridge.KeyType
import uniffi.rust_bridge.KeystoreAccessRules
import uniffi.rust_bridge.KeystoreDigest
import uniffi.rust_bridge.KeystorePadding
import uniffi.rust_bridge.NativeKeystore

/**
 * The key store the iMessage library keeps its device keys in: Android's hardware-backed
 * Keystore, so they never leave the phone's secure hardware. The library asks for keys by name;
 * every name gets [PREFIX] here, so its keys can never meet LightDecoder's own (the archive's and
 * the links' keys live in the same Keystore, which every process of the app shares).
 *
 * Formats the library expects: public keys as X.509 SubjectPublicKeyInfo (DER); ECDH takes the
 * peer's key in the same form and returns the raw shared secret; AES-GCM output is the 12-byte IV
 * followed by ciphertext and 16-byte tag; key import is Android's wrapped-key format under the
 * RSA key from [getImportWrapKey].
 */
class DirectKeystore(private val context: Context) : NativeKeystore {

    private val store: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun a(alias: String) = PREFIX + alias

    override fun supportsImport(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    override fun createKey(alias: String, type: KeyType, accessRules: KeystoreAccessRules) {
        val name = a(alias)
        check(!store.containsAlias(name)) { "Key '$alias' already exists." }
        val spec = spec(name, type, accessRules)
        if (type is KeyType.Aes) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply { init(spec) }.generateKey()
        } else {
            KeyPairGenerator.getInstance(algorithm(type), ANDROID_KEYSTORE).apply { initialize(spec) }.generateKeyPair()
        }
    }

    override fun destroyKey(alias: String) = store.deleteEntry(a(alias))

    override fun listKeys(): List<String> =
        store.aliases().toList().filter { it.startsWith(PREFIX) }.map { it.removePrefix(PREFIX) }

    override fun importKey(alias: String, type: KeyType, wrappedKey: ByteArray, accessRules: KeystoreAccessRules) {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) { "Key import needs Android 9." }
        val name = a(alias)
        check(!store.containsAlias(name)) { "Key '$alias' already exists." }
        val wrapSpec = KeyGenParameterSpec.Builder(a(WRAP_KEY), KeyProperties.PURPOSE_WRAP_KEY)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()
        store.setEntry(name, WrappedKeyEntry(wrappedKey, a(WRAP_KEY), "RSA/ECB/OAEPPadding", wrapSpec), null)
    }

    override fun getImportWrapKey(): ByteArray {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) { "Key import needs Android 9." }
        val name = a(WRAP_KEY)
        if (!store.containsAlias(name)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE).apply {
                initialize(
                    KeyGenParameterSpec.Builder(name, KeyProperties.PURPOSE_WRAP_KEY)
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                        .setBlockModes(KeyProperties.BLOCK_MODE_ECB)
                        .setKeySize(2048)
                        .build(),
                )
            }.generateKeyPair()
        }
        return getPublicKey(WRAP_KEY)
    }

    override fun getKeyType(alias: String): KeyType? {
        val key = when (val entry = store.getEntry(a(alias), null)) {
            is KeyStore.PrivateKeyEntry -> entry.privateKey
            is KeyStore.SecretKeyEntry -> entry.secretKey
            else -> return null
        }
        return try {
            when (key.algorithm) {
                KeyProperties.KEY_ALGORITHM_EC -> when ((key as? ECKey)?.params?.curve?.field?.fieldSize) {
                    256 -> KeyType.Ec(EcCurve.P256)
                    384 -> KeyType.Ec(EcCurve.P384)
                    else -> null
                }
                KeyProperties.KEY_ALGORITHM_AES -> {
                    val info = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
                        .getKeySpec(key as javax.crypto.SecretKey, KeyInfo::class.java) as KeyInfo
                    KeyType.Aes(info.keySize.toUShort())
                }
                KeyProperties.KEY_ALGORITHM_RSA -> {
                    val info = KeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
                        .getKeySpec(key, KeyInfo::class.java)
                    KeyType.Rsa(info.keySize.toUShort())
                }
                else -> null
            }
        } catch (e: java.security.GeneralSecurityException) {
            null
        }
    }

    override fun sign(alias: String, digest: KeystoreDigest, padding: KeystorePadding, data: ByteArray): ByteArray {
        val entry = store.getEntry(a(alias), null) as KeyStore.PrivateKeyEntry
        return Signature.getInstance(signatureAlgorithm(digest, entry.privateKey.algorithm)).run {
            initSign(entry.privateKey)
            update(data)
            sign()
        }
    }

    override fun verify(
        alias: String,
        digest: KeystoreDigest,
        padding: KeystorePadding,
        data: ByteArray,
        sig: ByteArray,
    ): Boolean {
        val entry = store.getEntry(a(alias), null) as KeyStore.PrivateKeyEntry
        return Signature.getInstance(signatureAlgorithm(digest, entry.privateKey.algorithm)).run {
            initVerify(entry.certificate.publicKey)
            update(data)
            verify(sig)
        }
    }

    override fun getPublicKey(alias: String): ByteArray =
        store.getCertificate(a(alias))?.publicKey?.encoded ?: error("No public key for '$alias'.")

    override fun derive(alias: String, peer: ByteArray): ByteArray {
        val private = (store.getEntry(a(alias), null) as KeyStore.PrivateKeyEntry).privateKey
        val peerKey = KeyFactory.getInstance(private.algorithm).generatePublic(X509EncodedKeySpec(peer))
        return KeyAgreement.getInstance("ECDH", ANDROID_KEYSTORE).run {
            init(private)
            doPhase(peerKey, true)
            generateSecret()
        }
    }

    override fun encrypt(alias: String, plaintext: ByteArray, mode: EncryptMode): ByteArray = when (mode) {
        is EncryptMode.Gcm -> {
            val key = (store.getEntry(a(alias), null) as? KeyStore.SecretKeyEntry)?.secretKey
                ?: error("No AES key '$alias'.")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
            val iv = cipher.iv ?: error("No IV for GCM.")
            iv + cipher.doFinal(plaintext)
        }
        is EncryptMode.Rsa -> {
            val public = (store.getEntry(a(alias), null) as? KeyStore.PrivateKeyEntry)?.certificate?.publicKey
                ?: error("No RSA key '$alias'.")
            rsaCipher(mode.v1).apply {
                val p = oaep(mode.v1)
                if (p != null) init(Cipher.ENCRYPT_MODE, public, p) else init(Cipher.ENCRYPT_MODE, public)
            }.doFinal(plaintext)
        }
    }

    override fun decrypt(alias: String, ciphertext: ByteArray, mode: EncryptMode): ByteArray = when (mode) {
        is EncryptMode.Gcm -> {
            require(ciphertext.size >= GCM_IV) { "GCM data is missing its IV." }
            val key = (store.getEntry(a(alias), null) as? KeyStore.SecretKeyEntry)?.secretKey
                ?: error("No AES key '$alias'.")
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, ciphertext, 0, GCM_IV))
            }.doFinal(ciphertext, GCM_IV, ciphertext.size - GCM_IV)
        }
        is EncryptMode.Rsa -> {
            val private = (store.getEntry(a(alias), null) as? KeyStore.PrivateKeyEntry)?.privateKey
                ?: error("No RSA key '$alias'.")
            rsaCipher(mode.v1).apply {
                val p = oaep(mode.v1)
                if (p != null) init(Cipher.DECRYPT_MODE, private, p) else init(Cipher.DECRYPT_MODE, private)
            }.doFinal(ciphertext)
        }
    }

    private fun spec(name: String, type: KeyType, rules: KeystoreAccessRules): KeyGenParameterSpec {
        var purposes = 0
        if (rules.canSign) purposes = purposes or KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        if (rules.canEncrypt) purposes = purposes or KeyProperties.PURPOSE_ENCRYPT
        if (rules.canDecrypt) purposes = purposes or KeyProperties.PURPOSE_DECRYPT
        if (rules.canAgree && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            purposes = purposes or KeyProperties.PURPOSE_AGREE_KEY
        }
        val b = KeyGenParameterSpec.Builder(name, purposes)
        if (rules.digests.isNotEmpty()) b.setDigests(*rules.digests.map(::digestName).toTypedArray())
        if (rules.blockModes.isNotEmpty()) {
            b.setBlockModes(
                *rules.blockModes.map {
                    if (it is EncryptMode.Gcm) KeyProperties.BLOCK_MODE_GCM else KeyProperties.BLOCK_MODE_ECB
                }.distinct().toTypedArray(),
            )
        }
        b.setEncryptionPaddings(
            *(rules.encryptionPaddings.map(::encryptionPaddingName).ifEmpty { listOf(KeyProperties.ENCRYPTION_PADDING_NONE) })
                .distinct().toTypedArray(),
        )
        if (rules.signaturePadding.isNotEmpty()) {
            b.setSignaturePaddings(*rules.signaturePadding.map(::signaturePaddingName).distinct().toTypedArray())
        }
        if (Build.VERSION.SDK_INT >= 35 && rules.mgf1Digests.isNotEmpty()) {
            b.setMgf1Digests(*rules.mgf1Digests.map(::digestName).toTypedArray())
        }
        b.setKeySize(size(type))
        if (type is KeyType.Ec) b.setAlgorithmParameterSpec(ECGenParameterSpec(curveName(type.v1)))
        if (rules.requireUser &&
            (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure
        ) {
            b.setUserAuthenticationRequired(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
            }
        }
        return b.build()
    }

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"

        /** Every key the iMessage library makes is stored under this prefix. */
        const val PREFIX = "imessage:"
        private const val WRAP_KEY = "keystore:wrap-key"
        private const val GCM_IV = 12

        fun algorithm(type: KeyType): String = when (type) {
            is KeyType.Ec -> KeyProperties.KEY_ALGORITHM_EC
            is KeyType.Aes -> KeyProperties.KEY_ALGORITHM_AES
            is KeyType.Rsa -> KeyProperties.KEY_ALGORITHM_RSA
        }

        fun size(type: KeyType): Int = when (type) {
            is KeyType.Ec -> if (type.v1 == EcCurve.P256) 256 else 384
            is KeyType.Aes -> type.v1.toInt()
            is KeyType.Rsa -> type.v1.toInt()
        }

        fun curveName(curve: EcCurve): String = if (curve == EcCurve.P256) "secp256r1" else "secp384r1"

        fun digestName(d: KeystoreDigest): String = when (d) {
            KeystoreDigest.SHA1 -> KeyProperties.DIGEST_SHA1
            KeystoreDigest.SHA256 -> KeyProperties.DIGEST_SHA256
            KeystoreDigest.SHA384 -> KeyProperties.DIGEST_SHA384
        }

        fun encryptionPaddingName(p: KeystorePadding): String = when (p) {
            is KeystorePadding.Pkcs1 -> KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1
            is KeystorePadding.Oaep -> KeyProperties.ENCRYPTION_PADDING_RSA_OAEP
            is KeystorePadding.None -> KeyProperties.ENCRYPTION_PADDING_NONE
        }

        fun signaturePaddingName(p: KeystorePadding): String = when (p) {
            is KeystorePadding.Pkcs1 -> KeyProperties.SIGNATURE_PADDING_RSA_PKCS1
            else -> throw IllegalArgumentException("Signature padding $p is not supported.")
        }

        /** "SHA256withECDSA", "SHA1withRSA", ...: the JCA name for a digest and key algorithm. */
        fun signatureAlgorithm(digest: KeystoreDigest, keyAlgorithm: String): String =
            digestName(digest).replace("-", "") + "with" + if (keyAlgorithm == "EC") "ECDSA" else keyAlgorithm

        fun rsaCipher(p: KeystorePadding): Cipher = when (p) {
            is KeystorePadding.Pkcs1 -> Cipher.getInstance("RSA/ECB/PKCS1Padding")
            is KeystorePadding.Oaep -> Cipher.getInstance("RSA/ECB/OAEPPadding")
            is KeystorePadding.None -> throw IllegalArgumentException("RSA without padding is not supported.")
        }

        fun oaep(p: KeystorePadding): OAEPParameterSpec? = (p as? KeystorePadding.Oaep)?.let {
            OAEPParameterSpec(digestName(it.md), "MGF1", MGF1ParameterSpec(digestName(it.mgf1)), PSource.PSpecified.DEFAULT)
        }
    }
}
