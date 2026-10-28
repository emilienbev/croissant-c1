package com.bleural.croissant.net

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** A hold's start frame: its words and nothing else, the model calls being the C1's (server session.py). */
class ProtocolTest {
    @Test fun aHoldIsDictationWithItsSpeechPickAndLiveWords() {
        val o = JSONObject(Protocol.start(16000, 1, stt = "ollama:whisper", partials = false, conv = "c_1"))
        assertEquals("start", o.getString("type"))
        assertEquals("dictate", o.getString("mode"))
        assertEquals(16000, o.getInt("rate"))
        assertEquals("pcm_s16le", o.getString("enc"))
        assertEquals("ollama:whisper", o.getString("stt"))
        assertFalse(o.getBoolean("partials"))
        assertEquals("c_1", o.getString("conv"))
        assertFalse(o.has("from"))
    }

    @Test fun theTranslatorsHoldSaysWhatLanguageIsSpoken() {
        assertEquals("fr", JSONObject(Protocol.start(16000, 1, from = "fr")).getString("from"))
    }

    @Test fun aHoldCarriesNoPromptNoPhotoAndNoChatPick() {
        val o = JSONObject(Protocol.start(16000, 1))
        for (k in listOf("system", "tools", "variant", "image", "chat", "prior", "now", "turn", "to")) assertFalse(k, o.has(k))
        assertEquals(setOf("type", "mode", "rate", "enc", "ch"), o.keys().asSequence().toSet())
    }
}
