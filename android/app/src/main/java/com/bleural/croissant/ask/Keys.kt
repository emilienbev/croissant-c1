package com.bleural.croissant.ask

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Seals a provider's key and opens it again; [aad] binds what is sealed to its provider, so one cannot pass for another. */
interface Sealer {
    /** The IV and the sealed bytes. */
    fun seal(plain: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray>
    fun open(iv: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray
}

/**
 * AES-256-GCM under one AndroidKeyStore key ([ALIAS]), made the first time a key is kept. No
 * user authentication and not `setUnlockedDeviceRequired`: the app is HOME and a hold can
 * come before the first unlock, and keystore2 opens a key without those flags in direct boot.
 * The key never leaves the keystore; a reflash or a wiped keystore loses it, and every key
 * sealed with it is typed again.
 */
class KeystoreSealer(private val alias: String = ALIAS) : Sealer {

    private fun secret(): SecretKey {
        val store = KeyStore.getInstance(STORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE).apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }

    override fun seal(plain: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> {
        // The keystore picks the IV: it refuses one given for encryption.
        val c = Cipher.getInstance(GCM).apply { init(Cipher.ENCRYPT_MODE, secret()); updateAAD(aad) }
        return c.iv to c.doFinal(plain)
    }

    override fun open(iv: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray =
        Cipher.getInstance(GCM).apply { init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, iv)); updateAAD(aad) }.doFinal(sealed)

    companion object {
        const val ALIAS = "croissant.keys"
        private const val STORE = "AndroidKeyStore"
        private const val GCM = "AES/GCM/NoPadding"
    }
}

/**
 * The providers' keys on this C1: keys.json in the device-protected files, beside
 * prompts.json, each key sealed on its own by [sealer] (iv and sealed bytes, base64), with
 * its last four characters for the pages (as the server's `/settings` shows a key, and only
 * for one of 12 or more). A key is opened in memory when a call needs it and goes only in
 * that provider's header. Nothing here logs, and no error carries a key.
 *
 * When the keystore cannot seal, a key is refused with why, never kept in clear. A change
 * that cannot be written is not made.
 */
class KeysStore(private val file: File, private val sealer: Sealer) {

    private data class Kept(val iv: String, val sealed: String, val hint: String?)

    @Volatile private var kept: Map<String, Kept> = emptyMap()

    init {
        val o = runCatching { JSONObject(file.readText()).getJSONObject("keys") }.getOrNull()
        kept = o?.keys()?.asSequence()?.mapNotNull { id ->
            o.optJSONObject(id)?.let { k ->
                val iv = k.optString("iv"); val sealed = k.optString("sealed")
                if (iv.isEmpty() || sealed.isEmpty()) null else id to Kept(iv, sealed, k.optString("hint").ifEmpty { null })
            }
        }?.toMap().orEmpty()
    }

    /** The providers that have a key kept. */
    val providers: Set<String> get() = kept.keys

    fun has(provider: String): Boolean = provider in kept

    /** Its last four characters, for a key of 12 or more; null otherwise, or with none kept. */
    fun hint(provider: String): String? = kept[provider]?.hint

    /** [key] kept for [provider], replacing any: null when kept, else why not. */
    @Synchronized
    fun save(provider: String, key: String): String? {
        val k = key.trim()
        refused(k)?.let { return it }
        val plain = k.toByteArray()
        val sealed = try {
            sealer.seal(plain, provider.toByteArray())
        } catch (e: Exception) {
            return "keys cannot be kept on this device (${e.javaClass.simpleName})"
        } finally {
            plain.fill(0)
        }
        val b64 = Base64.getEncoder()
        return commit(kept + (provider to Kept(b64.encodeToString(sealed.first), b64.encodeToString(sealed.second),
            if (k.length >= 12) k.takeLast(4) else null)))
    }

    /** [provider]'s key, opened: null when none is kept. Throws [IOException] when the kept one cannot be opened (the keystore lost it). */
    fun key(provider: String): String? {
        val k = kept[provider] ?: return null
        val b64 = Base64.getDecoder()
        val plain = try {
            sealer.open(b64.decode(k.iv), b64.decode(k.sealed), provider.toByteArray())
        } catch (e: Exception) {
            throw IOException("the key for $provider cannot be opened (${e.javaClass.simpleName}): type it again")
        }
        return String(plain).also { plain.fill(0) }
    }

    @Synchronized
    fun remove(provider: String): String? = if (provider !in kept) null else commit(kept - provider)

    private fun commit(k: Map<String, Kept>): String? = try {
        val o = JSONObject()
        for ((id, v) in k) o.put(id, JSONObject().put("iv", v.iv).put("sealed", v.sealed).apply { v.hint?.let { put("hint", it) } })
        writeAtomically(file, JSONObject().put("version", 1).put("keys", o).toString(2))
        kept = k
        null
    } catch (e: IOException) {
        "it could not be saved"
    }

    companion object {
        /** An OpenAI project key is about 160; this leaves room. */
        const val MAX = 512

        /** Why [key] (trimmed) cannot be kept, or null: the rules [save] holds it to, for a file checked before anything is kept. */
        fun refused(key: String): String? = when {
            key.isEmpty() -> "a key cannot be empty"
            key.any { it.isWhitespace() } -> "a key is one word, with no spaces"
            // What a header can carry: a pasted key may hide a zero-width space or a byte-order mark.
            key.any { it !in '!'..'~' } -> "a key is letters, digits and symbols only: type it again"
            key.length > MAX -> "a key is up to $MAX characters"
            else -> null
        }
    }
}
