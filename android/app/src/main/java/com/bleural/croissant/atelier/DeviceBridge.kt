package com.bleural.croissant.atelier

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The device's side of the bridge: [ask] is one call on the chat model picked in Settings,
 * made by the C1 itself under the app rules ([asker], Core's, ask/Calls.kt `appAsk`); [fetch]
 * goes to the internet only, [cue] to the device's sounds.
 *
 * The tailnet and the LAN are out of an app's reach: croissant-server answers anyone there
 * without a question, so an app reaching it could spend the provider keys. [fetch] refuses private, tailnet and local addresses, and any
 * inside one of the device's own networks (a LAN on global IPv6), whether named, typed as an
 * address, or reached by a redirect: every socket checks where it goes before it connects
 * ([PublicSockets]). The router's public IPv4, which some routers hairpin back to the LAN,
 * cannot be told from any other address here: that one stays open.
 */
class DeviceBridge(
    private val asker: suspend (app: String, text: String) -> String,
    private val userAgent: String,
    private val play: (String) -> Unit,
) : Bridge {

    private val webClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .proxy(Proxy.NO_PROXY)
        .dns(PublicDns)
        .socketFactory(PublicSockets)
        .build()

    /** Why it could not be asked (no model, no key, the question too long, the provider refusing) comes back as the app's error. */
    override suspend fun ask(app: InstalledApp, text: String): String = try {
        asker(app.id, text)
    } catch (e: CancellationException) {
        throw e
    } catch (e: BadApp) {
        throw e
    } catch (e: Exception) {
        throw BadApp(e.message ?: "the model did not answer")
    }

    /**
     * 30 seconds at most, on OkHttp's threads, never the app's or the IO pool's, and cancelled
     * with its coroutine. A refused address reads as one that does not answer, so an app
     * cannot map the LAN by which is which.
     */
    override suspend fun fetch(app: InstalledApp, url: String): Fetched {
        val u = url.trim()
        if (!u.startsWith("https://") && !u.startsWith("http://")) throw BadApp("fetch takes an http or https address")
        val req = try {
            Request.Builder().url(u).header("User-Agent", userAgent).build()
        } catch (e: IllegalArgumentException) {
            throw BadApp("fetch: $u is not an address")
        }
        return try {
            webClient.newCall(req).fetched()
        } catch (e: IOException) {
            throw BadApp("fetch: ${req.url.host} cannot be reached")
        }
    }

    private suspend fun Call.fetched(): Fetched = suspendCancellableCoroutine { c ->
        c.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = c.resumeWithException(e)
            override fun onResponse(call: Call, response: Response) {
                runCatching { response.use { read(it) } }.fold({ c.resume(it) }, { c.resumeWithException(it) })
            }
        })
    }

    private fun read(r: Response): Fetched {
        val len = r.body.contentLength()
        if (len > MAX_BODY) throw BadApp("fetch: the answer is over ${MAX_BODY / 1024} kB")
        val bytes = r.body.source().let { src ->
            src.request(MAX_BODY + 1L)
            if (src.buffer.size > MAX_BODY) throw BadApp("fetch: the answer is over ${MAX_BODY / 1024} kB")
            src.buffer.readByteArray()
        }
        return Fetched(r.code, String(bytes, Charsets.UTF_8))
    }

    override fun cue(name: String) = play(name)

    /** Names that resolve only to refused addresses fail before any connection is made. */
    private object PublicDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val all = Dns.SYSTEM.lookup(hostname)
            val nets = localNets()
            return all.filterNot { refused(it, nets) }.ifEmpty { throw NotPublic(hostname) }
        }
    }

    /**
     * Where every connection is made, names, typed addresses and redirects alike, with no proxy
     * between: a refused address throws before the socket connects. OkHttp lays TLS over the
     * connected socket, so https goes through here too.
     */
    internal object PublicSockets : SocketFactory() {
        override fun createSocket(): Socket = PublicSocket()
        override fun createSocket(host: String, port: Int): Socket = PublicSocket().apply { connect(InetSocketAddress(host, port)) }
        override fun createSocket(host: String, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            PublicSocket().apply { bind(InetSocketAddress(localHost, localPort)); connect(InetSocketAddress(host, port)) }
        override fun createSocket(host: InetAddress, port: Int): Socket = PublicSocket().apply { connect(InetSocketAddress(host, port)) }
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            PublicSocket().apply { bind(InetSocketAddress(localAddress, localPort)); connect(InetSocketAddress(address, port)) }
    }

    private class PublicSocket : Socket() {
        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            val a = (endpoint as? InetSocketAddress)?.address
            if (a == null || refused(a, localNets())) throw NotPublic(a?.hostAddress ?: "an unresolved address")
            super.connect(endpoint, timeout)
        }
    }

    private class NotPublic(host: String) : UnknownHostException(host)

    /** [bits] leading bits of [base]: a network. */
    class Net(val base: ByteArray, val bits: Int) {
        operator fun contains(a: InetAddress): Boolean {
            val b = a.address
            if (b.size != base.size) return false
            for (i in 0 until bits) {
                val m = 0x80 ushr (i % 8)
                if ((b[i / 8].toInt() and m) != (base[i / 8].toInt() and m)) return false
            }
            return true
        }

        companion object {
            fun of(cidr: String): Net = cidr.split('/').let { (a, n) -> Net(InetAddress.getByName(a).address, n.toInt()) }
        }
    }

    companion object {
        const val MAX_BODY = 1024 * 1024

        /**
         * Loopback, link-local, private, the tailnet's 100.64/10 and IPv6's fc00::/7 (the
         * tailnet's too), multicast, the reserved ranges, local-use NAT64 and 6to4.
         */
        private val PRIVATE = listOf(
            "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16", "172.16.0.0/12",
            "192.168.0.0/16", "198.18.0.0/15", "224.0.0.0/4", "240.0.0.0/4",
            "::/128", "::1/128", "fc00::/7", "fe80::/10", "fec0::/10", "ff00::/8", "64:ff9b:1::/48", "2002::/16",
        ).map(Net::of)

        /** NAT64's well-known prefix, an IPv4 address in its last four bytes: how an IPv6-only network reaches IPv4. */
        private val NAT64 = Net.of("64:ff9b::/96")

        fun isPrivate(a: InetAddress): Boolean = refused(a, emptyList())

        /** [a] is private, or inside one of [nets], the device's own ([localNets]); a NAT64 address as the IPv4 one it carries. */
        fun refused(a: InetAddress, nets: List<Net>): Boolean =
            if (a in NAT64) refused(InetAddress.getByAddress(a.address.copyOfRange(12, 16)), nets)
            else a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress ||
                a.isMulticastAddress || PRIVATE.any { a in it } || nets.any { a in it }

        /** The device's own addresses and the networks they are on, read at each check (they change with Wi-Fi). Unreadable, everything is refused. */
        fun localNets(): List<Net> = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().flatMap { it.interfaceAddresses }
                .mapNotNull { ia -> ia.address?.let { net(it, ia.networkPrefixLength.toInt()) } }
        } catch (e: Exception) {
            listOf(Net(ByteArray(4), 0), Net(ByteArray(16), 0))
        }

        /** [a]'s network, [prefix] bits; one wider than a LAN's (under /8, /32 for IPv6) is the address alone. */
        fun net(a: InetAddress, prefix: Int): Net {
            val all = a.address.size * 8
            return Net(a.address, prefix.takeIf { it in (if (a is Inet4Address) 8 else 32)..all } ?: all)
        }
    }
}
