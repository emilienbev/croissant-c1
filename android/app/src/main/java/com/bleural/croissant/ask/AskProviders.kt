package com.bleural.croissant.ask

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * The providers, their keys and their lists kept together (Core's, through these): a key
 * goes only to the address it was typed for, so moving a provider drops its key, and a list
 * is asked again once its provider or its key changes.
 */
class AskProviders(private val providers: ProvidersStore, private val keys: KeysStore, private val listings: ListingsStore) {

    /** Every provider added, Ollama first, as reached with the server at [host]. */
    fun all(host: String): List<Provider> = providers.providers(host)

    /** The providers that can answer: a key kept, or none needed. */
    fun ready(host: String): List<Provider> = all(host).filter { it.ready(keys.has(it.id)) }

    /** Add [id] or change it: null when kept, else why not. A new address leaves its key behind. */
    @Synchronized
    fun save(id: String, spec: ProviderSpec = ProviderSpec()): String? {
        val before = providers.specs[id]?.let { address(id, it) }
        providers.save(id, spec)?.let { return it }
        if (before != null && before != address(id, spec)) keys.remove(id)?.let { return it }
        listings.drop(id)
        return null
    }

    /** [id] gone, its key and its list with it; Ollama goes back to its preset. */
    @Synchronized
    fun remove(id: String): String? {
        keys.remove(id)?.let { return it }
        listings.drop(id)
        return providers.remove(id)
    }

    /** A key for [id], the provider added if it was not: null when kept, else why not. */
    @Synchronized
    fun saveKey(id: String, key: String): String? {
        if (id !in providers.specs) save(id)?.let { return it }
        return keys.save(id, key).also { if (it == null) listings.drop(id) }
    }

    fun hint(id: String): String? = keys.hint(id)

    /** What [id] was added with (its address and name where they are not its preset's); null for one not added. */
    fun spec(id: String): ProviderSpec? = providers.specs[id]

    /** [id]'s key, opened: for the export a phone saves only. Throws [IOException] when the keystore lost it. */
    fun key(id: String): String? = keys.key(id)

    fun hasKey(id: String): Boolean = keys.has(id)

    fun listing(id: String): Listing? = listings.listed[id]

    /** What every provider last listed, by id. */
    fun listings(): Map<String, Listing> = listings.listed

    /** Ask [id] for its models with its key and keep the list. Throws why not (the key never in it). */
    suspend fun list(id: String, host: String, http: OkHttpClient = Http.client): Listing {
        val p = all(host).firstOrNull { it.id == id } ?: throw IOException("there is no provider $id")
        return withContext(Dispatchers.IO) { listings.keep(id, Lister.list(p, keys.key(id), http)) }
    }

    /**
     * [id] as [spec], with [key] (null: the one kept, while its address stays the same), asked
     * for its models before anything is kept: kept, the provider, the key and the list, only if
     * that works, and the list comes back. Throws [ProvidersError] for a provider or a key that
     * cannot be, and why the provider would not list (the key never in it); nothing is kept then.
     */
    suspend fun tryOut(id: String, spec: ProviderSpec, key: String?, host: String, http: OkHttpClient = Http.client): Listing {
        val typed = key?.trim()
        typed?.let { KeysStore.refused(it) }?.let { throw ProvidersError(it) }
        val p = Providers.resolve(id, spec, host)
        val before = providers.specs[id]
        val same = before != null && address(id, before) == address(id, spec)
        val listing = withContext(Dispatchers.IO) { Lister.list(p, typed ?: if (same) keys.key(id) else null, http) }
        synchronized(this) {
            if (before != spec) save(id, spec)?.let { throw IOException(it) }
            if (typed != null) keys.save(id, typed)?.let { why ->
                if (before == null) providers.remove(id)
                throw IOException(why)
            }
            return listings.keep(id, listing)
        }
    }

    /** Where [spec] sends [id]'s calls, whatever the server's host: what its key was typed for. */
    private fun address(id: String, spec: ProviderSpec): String? = runCatching { Providers.resolve(id, spec, "").url }.getOrNull()
}
