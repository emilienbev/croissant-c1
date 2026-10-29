package com.bleural.croissant.ask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The keystore's sealing done in software, as AndroidKeyStore does it: AES-256-GCM, the provider bound in. */
internal class SoftSealer : Sealer {
    private val secret: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    override fun seal(plain: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secret); updateAAD(aad) }
        return c.iv to c.doFinal(plain)
    }
    override fun open(iv: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, iv)); updateAAD(aad) }.doFinal(sealed)
}

/** The keys store, on [SoftSealer]. */
class KeysStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val gemini = "AIza00000000000000000000000000000000MNOP"

    @Test fun aKeyIsPrintableAsciiOnly() {
        val keys = KeysStore(file(), sealer)
        // As pasted from a page: a zero-width space, a byte-order mark, an accented letter.
        for (bad in listOf("$gemini\u200B", "\uFEFF$gemini", "sk-proj-cl\u00e9-0000000000")) {
            val why = keys.save("gemini", bad)
            assertNotNull(why)
            assertFalse(gemini in why!!)
        }
        assertFalse(keys.has("gemini"))
    }

    private val sealer = SoftSealer()
    private fun file() = File(tmp.root, "keys.json")

    @Test fun aKeyIsKeptSealedAndOpensAgain() {
        val keys = KeysStore(file(), sealer)
        assertNull(keys.save("gemini", "  $gemini\n"))
        assertEquals(gemini, keys.key("gemini"))
        // Read again from the file, as after a restart.
        assertEquals(gemini, KeysStore(file(), sealer).key("gemini"))
        assertTrue(KeysStore(file(), sealer).has("gemini"))
    }

    @Test fun theFileHoldsNoKeyNorItsLastEight() {
        KeysStore(file(), sealer).save("gemini", gemini)
        val text = file().readText()
        assertFalse(gemini in text)
        assertFalse(gemini.takeLast(8) in text)
    }

    @Test fun aHintIsTheLastFourOfALongKeyOnly() {
        val keys = KeysStore(file(), sealer)
        keys.save("gemini", gemini)
        keys.save("mine", "short-key")
        assertEquals("MNOP", keys.hint("gemini"))
        assertNull(keys.hint("mine"))
        assertNull(keys.hint("openai"))
    }

    @Test fun aKeystoreThatCannotSealRefusesAndKeepsNothingInClear() {
        val broken = object : Sealer {
            override fun seal(plain: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> = throw ProviderException("Keystore operation failed")
            override fun open(iv: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray = throw ProviderException("no")
        }
        val keys = KeysStore(file(), broken)
        val why = keys.save("gemini", gemini)
        assertNotNull(why)
        assertFalse(gemini in why!!)
        assertFalse(keys.has("gemini"))
        assertFalse(file().exists() && gemini in file().readText())
    }

    @Test fun aKeyMovedToAnotherProviderDoesNotOpen() {
        KeysStore(file(), sealer).save("gemini", gemini)
        file().writeText(file().readText().replace("\"gemini\"", "\"openai\""))
        val e = assertThrows(IOException::class.java) { KeysStore(file(), sealer).key("openai") }
        assertFalse(gemini in e.message.orEmpty())
    }

    @Test fun badKeysAreRefused() {
        val keys = KeysStore(file(), sealer)
        assertNotNull(keys.save("gemini", "   "))
        assertNotNull(keys.save("gemini", "two words"))
        assertNotNull(keys.save("gemini", "x".repeat(KeysStore.MAX + 1)))
        assertFalse(keys.has("gemini"))
    }

    @Test fun removedIsGone() {
        val keys = KeysStore(file(), sealer)
        keys.save("gemini", gemini)
        keys.save("openai", "sk-proj-fake-key-000000000000")
        assertNull(keys.remove("gemini"))
        assertNull(keys.key("gemini"))
        assertEquals(setOf("openai"), KeysStore(file(), sealer).providers)
    }

    @Test fun aCorruptFileStartsEmpty() {
        file().writeText("{not json")
        assertTrue(KeysStore(file(), sealer).providers.isEmpty())
    }
}
