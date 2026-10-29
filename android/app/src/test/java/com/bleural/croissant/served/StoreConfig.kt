package com.bleural.croissant.served

import com.bleural.croissant.ask.AskProviders
import com.bleural.croissant.ask.KeysStore
import com.bleural.croissant.ask.Listing
import com.bleural.croissant.ask.ListingsStore
import com.bleural.croissant.ask.ProviderSpec
import com.bleural.croissant.ask.Providers
import com.bleural.croissant.ask.ProvidersError
import com.bleural.croissant.ask.ProvidersStore
import com.bleural.croissant.ask.SoftSealer
import com.bleural.croissant.data.AskCatalog
import com.bleural.croissant.data.Prompts
import com.bleural.croissant.data.PromptsStore
import com.bleural.croissant.net.ModelSlot
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** A C1's settings on the real stores in [dir] (the keystore in software), as Core gives them to the page. */
internal class StoreConfig(dir: File, private val host: String = "100.100.100.7") : Config {

    private val listings = ListingsStore(File(dir, "listings.json"))
    val ask = AskProviders(ProvidersStore(File(dir, "providers.json")), KeysStore(File(dir, "keys.json"), SoftSealer()), listings)
    val store = PromptsStore(File(dir, "prompts.json"), CATALOG)
    private val picked = mutableMapOf<ModelSlot, String>()

    /** What a provider's API answers [list] with. */
    var lister: (String) -> Result<Listing> = { Result.failure(IOException("not listed")) }

    override fun providers() = ask.all(host)
    override fun spec(id: String) = ask.spec(id)
    override fun hint(id: String) = ask.hint(id)
    override fun hasKey(id: String) = ask.hasKey(id)
    override fun listing(id: String) = ask.listing(id)
    override fun saveProvider(id: String, spec: ProviderSpec) = ask.save(id, spec)
    override fun removeProvider(id: String) = ask.remove(id)
    override fun saveKey(id: String, key: String) = ask.saveKey(id, key)
    override fun key(id: String) = ask.key(id)
    override suspend fun list(id: String) = lister(id).onSuccess { listings.keep(id, it) }

    /** What [tryProvider] was last asked with: the id, the spec and the key. */
    var tried: Triple<String, ProviderSpec, String?>? = null

    /** As Core's: kept only if [lister] lists, the key never in an error. */
    override suspend fun tryProvider(id: String, spec: ProviderSpec, key: String?): Result<Listing> {
        tried = Triple(id, spec, key)
        return runCatching {
            key?.let { KeysStore.refused(it) }?.let { throw ProvidersError(it) }
            Providers.resolve(id, spec, host)
            val l = lister(id).getOrThrow()
            ask.save(id, spec)?.let { throw IOException(it) }
            key?.let { ask.saveKey(id, it) }?.let { throw IOException(it) }
            listings.keep(id, l)
        }
    }

    var place: String? = "macbook"
    override fun place() = place

    override val prompts: Prompts get() = store.prompts
    override fun addPrompt(name: String, from: String?) = store.add(name, from)
    override fun renamePrompt(name: String, to: String) = store.rename(name, to)
    override fun savePrompt(name: String, text: String) = store.save(name, text)
    override fun deletePrompt(name: String) = store.delete(name)
    override fun resetPrompt(name: String) = store.reset(name)
    override fun pickPrompt(entry: String, name: String?) = store.pick(entry, name)

    override val modelPicks: Map<ModelSlot, String> get() = picked.toMap()
    override fun pickModel(slot: ModelSlot, id: String?) {
        if (id == null) picked -= slot else picked[slot] = id
    }

    companion object {
        val CATALOG = AskCatalog(JSONObject()
            .put("tools", JSONArray()).put("prompts", JSONObject(mapOf("default" to "Be brief.", "gemma" to "Be Gemma.")))
            .put("photo", JSONObject()).put("parts", JSONObject().put("tools", "").put("apps", "").put("clock", ""))
            .put("suggest", JSONObject().put("gemma4", "gemma")).put("cases", JSONArray()).toString())

        const val GEMINI = "AIza00000000000000000000000000000000MNOP"
        const val OPENAI = "sk-proj-fake-key-for-tests-0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000Z9y8"
    }
}
